// Copyright Andreas Wendleder 2026
// CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util.Cat
import Instructions._

/** Centralized MMIO address map — single source of truth for hardware and firmware.
  *
  * Address decoding (inherited from TinyQV by Michael Bell):
  *   SoC peripherals:  Cat(addr[27:6], addr[1:0]) === SOC_COMPARE  → addr[5:2] selects peripheral
  // User peripherals: addr[10:0] = 11-bit sub-address (2KB for sub-decoding)
  //   addr[10:9] selects sub-peripheral (4 slots)
  //   addr[8:0] selects offset within peripheral (512 bytes)
  *
  // Full CPU address formulas:
  //   SoC:  soc_base  + index  × 4     (e.g. ID:   0x02000000 + 2×4  = 0x02000008)
  //   User: user_base + select × 512   (e.g. UART: 0x08000000 + 2×512 = 0x08000400)
  //   Sub:  user_base + select × 512 + offset
  */
object MmioMap {
  // --- Magic constants from Michael Bell's TinyQV address decoder ---
  // These are arbitrary bit patterns chosen to identify peripheral regions
  // within the 28-bit address space. They cannot be derived from anything
  // simpler — they are the foundational design choices of the bus decoder.
  //
  // SoC peripherals:  Cat(addr[27:6], addr[1:0]) is compared against SOC_REGION_ID
  // User peripherals: addr[27:11]                is compared against USER_REGION_ID
  // Both regions overlap at 0x08000000; the SoC check fires first and takes priority.
  private val SOC_REGION_ID  = 0x800000  // 24-bit comparison value
  private val USER_REGION_ID = 0x10000   // 17-bit comparison value

  // --- Address region abstraction ---
  case class AddrRegion(
    matchFn: UInt => Bool,    // Hardware address comparison
    indexHi: Int,             // High bit of the peripheral index
    indexLo: Int              // Low bit of the peripheral index
  ) {
    /** Check if an address is in this region. */
    def matches(addr: UInt): Bool = matchFn(addr)
    /** Extract the peripheral index bits from an address. */
    def index(addr: UInt): UInt = addr(indexHi, indexLo)
  }

  // SoC peripherals: addr[5:2] = 4-bit peripheral index (16 slots × 4 bytes)
  val socRegion = AddrRegion(
    matchFn  = addr => Cat(addr(27, 6), addr(1, 0)) === SOC_REGION_ID.U,
    indexHi  = 5, indexLo = 2
  )
  // User peripherals: addr[10:0] = 11-bit sub-address (2KB for sub-decoding)
  val userRegion = AddrRegion(
    matchFn  = addr => addr(27, 11) === USER_REGION_ID.U,
    indexHi  = 10, indexLo = 0
  )

  // --- CPU-visible base address for user peripherals (for C header generation) ---
  // Both regions overlap at 0x08000000, but for firmware the user peripheral
  // addresses are what matter (SoC peripherals are internal to the SoC).
  val USER_BASE = 0x08000000

  // --- SoC peripheral indices (addr[5:2], used in Project.scala SoCLogic) ---
  val PERI_NONE              = 0x0
  val PERI_ID                = 0x2
  val PERI_GPIO_OUT_SEL      = 0x3
  val PERI_DEBUG_UART        = 0x6
  val PERI_DEBUG_UART_STATUS = 0x7
  val PERI_DEBUG_UART_BAUD   = 0x8
  val PERI_TIME_LIMIT        = 0xB
  val PERI_DEBUG             = 0xC
  val PERI_USER              = 0xF

  // --- User peripheral selects (addr[10:9], used in Peripherals.scala) ---
  val USER_PERI_GPIO = 1
  val USER_PERI_UART = 2
  val USER_PERI_BORG = 3

  // --- TinyQV bus conventions ---
  val BUS_IDLE = 3  // data_write_n / data_read_n = 0b11 means no operation

  // --- System clock (single source of truth for FPGA builds) ---
  val CLOCK_MHZ = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  val FPGA_CLOCK_HZ = CLOCK_MHZ * 1000000
  val UART_BAUD_DEFAULT = FPGA_CLOCK_HZ / 115200

  // --- User peripheral address field positions (within 11-bit addr_in) ---
  val USER_PERI_SEL_HI   = 10  // addr_in(10:9) selects sub-peripheral (2-bit, 4 slots)
  val USER_PERI_SEL_LO   = 9
  val USER_SUB_ADDR_HI   = 8   // addr_in(8:0) is the 9-bit sub-register address
  val USER_SUB_ADDR_LO   = 0
  val GPIO_FUNC_SEL_IDX_HI = 4 // addr_in(4:2) selects which pin's func_sel
  val GPIO_FUNC_SEL_IDX_LO = 2

  // GPIO (tinyQV_peripherals in Peripherals.scala)
  val GPIO_OUT_OFFSET    = 0   // GPIO output register
  val GPIO_IN_OFFSET     = 4   // GPIO input register (read-only)
  val GPIO_FUNC_SEL_BIT  = 5   // Bit 5 high = func_sel register space

  // UART (PeriUart.scala)
  val UART_TX_OFFSET     = 0   // TX data write / RX data read
  val UART_STATUS_OFFSET = 4   // Status register
  val UART_BAUD_OFFSET   = 8   // Baud divider

  // Borg GPU (Borg.scala)
  val BORG_NUM_REGS       = 32
  val BORG_REG_OFFSET     = 0    // Register file base (32 × 16-bit)
  val BORG_IMEM_SLOTS     = 56   // reduced from 64 to fit uniform buffer; shaders total ~50 insns
  val BORG_IMEM_OFFSET    = BORG_REG_OFFSET + (BORG_NUM_REGS * 4)  // Instruction memory base (32 × 32-bit)
  val BORG_IMEM_END       = BORG_IMEM_OFFSET + (BORG_IMEM_SLOTS * 4)
  val BORG_ITER_BBOX_OFFSET = BORG_IMEM_END      // Pixel iterator: write bbox
  val BORG_ITER_OFFSET      = BORG_ITER_BBOX_OFFSET + 4  // Pixel iterator: advance
  val BORG_CONTROL_OFFSET   = BORG_ITER_OFFSET + 4  // Control / status register
  val BORG_FRAG_PC_OFFSET   = BORG_CONTROL_OFFSET + 4 // Fragment shader start PC for auto-chaining

  // Uniform buffer (2 pages × 32 entries = 64 hardware entries; scaffolding until DMA in step 16)
  // MMIO window covers one page (32 entries); uniformWritePage selects the target page.
  val BORG_UNIFORM_PAGE_ENTRIES = 32
  val BORG_UNIFORM_PAGES        = 2
  val BORG_UNIFORM_ENTRIES      = BORG_UNIFORM_PAGE_ENTRIES * BORG_UNIFORM_PAGES // 64 hardware entries
  val BORG_UNIFORM_OFFSET       = BORG_FRAG_PC_OFFSET + 4
  val BORG_UNIFORM_END          = BORG_UNIFORM_OFFSET + BORG_UNIFORM_PAGE_ENTRIES * 4  // MMIO window = 1 page

  // Tile buffer (Step 11.2): indexed read for flush, clear via CTRL
  // Write CTRL to set pixel index (triggers BRAM read), then read RG/BZ.
  val BORG_TILE_CTRL_OFFSET = BORG_UNIFORM_END       // write: bits[3:0]=idx, bit[4]=clear
  val BORG_TILE_RG_OFFSET   = BORG_TILE_CTRL_OFFSET + 4  // read: {R[31:16], G[15:0]}
  val BORG_TILE_BZ_OFFSET   = BORG_TILE_RG_OFFSET + 4    // read: {B[31:16], Z[15:0]}

  // Borg pixel iterator configuration
  val BORG_ITER_COORD_BITS    = 6
  val BORG_ITER_COORD_MASK    = (1 << BORG_ITER_COORD_BITS) - 1

  // SPIR-B instruction format sizing
  val SPIRB_INSTR_BYTES       = 4
  
  // Iterator reading layout (from BORG_ITER)
  val BORG_ITER_X_SHIFT       = 0
  val BORG_ITER_Y_SHIFT       = BORG_ITER_X_SHIFT + BORG_ITER_COORD_BITS
  val BORG_ITER_VALID_SHIFT   = BORG_ITER_Y_SHIFT + BORG_ITER_COORD_BITS
  val BORG_ITER_INSIDE_SHIFT  = BORG_ITER_VALID_SHIFT + 1

  // Iterator BBOX packing layout (to BORG_ITER_BBOX)
  val BORG_ITER_BBOX_X0_SHIFT = 0
  val BORG_ITER_BBOX_Y0_SHIFT = BORG_ITER_BBOX_X0_SHIFT + BORG_ITER_COORD_BITS
  val BORG_ITER_BBOX_X1_SHIFT = BORG_ITER_BBOX_Y0_SHIFT + BORG_ITER_COORD_BITS
  val BORG_ITER_BBOX_Y1_SHIFT = BORG_ITER_BBOX_X1_SHIFT + BORG_ITER_COORD_BITS

  // Borg control register bits (write to BORG_CONTROL)
  val BORG_CTL_START = 1  // bit 0: start execution
  val BORG_CTL_RESET = 2  // bit 1: reset pipeline
  val BORG_CTL_PC_SHIFT = 5    // bit 5: start of PC offset
  val BORG_CTL_PC_BITS  = 6    // width of PC offset
  val BORG_CTL_PC_MSB   = BORG_CTL_PC_SHIFT + BORG_CTL_PC_BITS - 1
  val BORG_CTL_PC_LSB   = BORG_CTL_PC_SHIFT
  val BORG_CTL_PC_MASK  = (1 << BORG_CTL_PC_BITS) - 1

  // Borg status register bits (read from BORG_STATUS)
  val BORG_STS_IDLE  = 2  // bit 1: pipeline idle (not running)



  // --- PSRAM addresses (QSPI memory space, not peripheral space) ---
  val PSRAM_BASE       = 0x01001000  // CPU address
  val PSRAM_SPI_BASE   = 0x001000    // SPI/QSPI address (24-bit)
  val PSRAM_OUT_OFFSET = 128         // PSRAM_OUT(n) = PSRAM_IN(n + OUT_OFFSET)

  // --- Shared PSRAM layout (used by both firmware and host) ---
  val TEX_PSRAM_OFFSET = 4200        // Texture data starts here (word index)
  val DONE_MARKER      = 0xDEAD      // Frame completion sentinel
  val STARTUP_DELAY_CYCLES = 10000   // Convenience delay iterations

  // --- Chisel UInt accessors for hardware ---
  def socPeriU(idx: Int): UInt = idx.U(4.W)
  def userPeriU(idx: Int): UInt = idx.U

  // --- Full address computation helpers ---
  def userAddr(select: Int, offset: Int): Int = USER_BASE + select * 512 + offset

  // --- Derived Base Addresses for Firmware / Host ---
  val USER_PERI_STRIDE             = 1 << (USER_SUB_ADDR_HI + 1)
  val GPIO_BASE                    = USER_BASE + USER_PERI_GPIO * USER_PERI_STRIDE
  val UART_BASE                    = USER_BASE + USER_PERI_UART * USER_PERI_STRIDE
  val BORG_BASE                    = USER_BASE + USER_PERI_BORG * USER_PERI_STRIDE

  val SOC_PERI_ID_OFFSET           = PERI_ID * 4
  val SOC_PERI_GPIO_OUT_SEL_OFFSET = PERI_GPIO_OUT_SEL * 4
  val SOC_PERI_DEBUG_UART_OFFSET   = PERI_DEBUG_UART * 4
  val SOC_PERI_TIME_LIMIT_OFFSET   = PERI_TIME_LIMIT * 4
}

