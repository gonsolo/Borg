// Copyright Andreas Wendleder 2026
// CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util.Cat
import Instructions._

/** Centralized MMIO address map — bus decoder and SoC peripheral indices.
  *
  * Register-level definitions (GPIO, UART, Borg GPU, PSRAM) have been migrated
  * to SystemRDL files in hardware/rdl/.  This object retains only the TinyQV
  * bus decoder logic and SoC-level constants that cannot be expressed in RDL.
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

  // --- User peripheral address field positions (within 11-bit addr_in) ---
  val USER_PERI_SEL_HI   = 10  // addr_in(10:9) selects sub-peripheral (2-bit, 4 slots)
  val USER_PERI_SEL_LO   = 9
  val USER_SUB_ADDR_HI   = 8   // addr_in(8:0) is the 9-bit sub-register address
  val USER_SUB_ADDR_LO   = 0

  // GPIO func_sel uses non-standard address bit decoding (not register offsets).
  // These must stay in Scala for Peripherals.scala hardware decode.
  val GPIO_FUNC_SEL_BIT    = 5   // Bit 5 high = func_sel register space
  val GPIO_FUNC_SEL_IDX_HI = 4   // addr_in(4:2) selects which pin's func_sel
  val GPIO_FUNC_SEL_IDX_LO = 2

  // GPIO register offsets (used in Peripherals.scala address comparisons)
  val GPIO_OUT_OFFSET = 0
  val GPIO_IN_OFFSET  = 4

  // --- Chisel UInt accessors for hardware ---
  def socPeriU(idx: Int): UInt = idx.U(4.W)
  def userPeriU(idx: Int): UInt = idx.U
}
