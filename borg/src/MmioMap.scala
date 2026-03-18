// Copyright Andreas Wendleder 2026
// CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util.Cat

/** Centralized MMIO address map — single source of truth for hardware and firmware.
  *
  * Address decoding (inherited from TinyQV by Michael Bell):
  *   SoC peripherals:  Cat(addr[27:6], addr[1:0]) === SOC_COMPARE  → addr[5:2] selects peripheral
  *   User peripherals: addr[27:11] === USER_COMPARE                → addr[10:6] selects sub-peripheral
  *
  * Full CPU address formulas:
  *   SoC:  soc_base  + index  × 4     (e.g. ID:   0x02000000 + 2×4  = 0x02000008)
  *   User: user_base + select × 64    (e.g. UART: 0x08000000 + 2×64 = 0x08000080)
  *   Sub:  user_base + select × 64 + offset
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

  // --- User peripheral selects (addr[10:6], used in Peripherals.scala) ---
  val USER_PERI_GPIO = 1
  val USER_PERI_UART = 2
  val USER_PERI_BORG = 3

  // --- TinyQV bus conventions ---
  val BUS_IDLE = 3  // data_write_n / data_read_n = 0b11 means no operation

  // --- User peripheral address field positions (within 11-bit addr_in) ---
  val USER_PERI_SEL_HI   = 10  // addr_in(10:6) selects sub-peripheral
  val USER_PERI_SEL_LO   = 6
  val USER_SUB_ADDR_HI   = 5   // addr_in(5:0) is the sub-register address
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
  val BORG_REG_OFFSET     = 0   // Register file base (8 × 16-bit)
  val BORG_IMEM_OFFSET    = 32  // Instruction memory base (4 × 16-bit)
  val BORG_CONTROL_OFFSET = 60  // Control / status register

  // --- PSRAM addresses (QSPI memory space, not peripheral space) ---
  val PSRAM_BASE       = 0x01001000  // CPU address
  val PSRAM_SPI_BASE   = 0x001000    // SPI/QSPI address (24-bit)
  val PSRAM_OUT_OFFSET = 128         // PSRAM_OUT(n) = PSRAM_IN(n + OUT_OFFSET)

  // --- Shared PSRAM layout (used by both firmware and host) ---
  val TEX_PSRAM_OFFSET = 4200        // Texture data starts here (word index)
  val DONE_MARKER      = 0xDEAD      // Frame completion sentinel

  // --- Chisel UInt accessors for hardware ---
  def socPeriU(idx: Int): UInt = idx.U(4.W)
  def userPeriU(idx: Int): UInt = idx.U

  // --- Full address computation helpers ---
  private def userAddr(select: Int, offset: Int): Int = USER_BASE + select * 64 + offset

  /** Emit C header with all MMIO addresses. */
  def emitHeader(path: String): Unit = {
    val w = new java.io.PrintWriter(path)
    w.println("// Auto-generated by MmioMap.scala — do not edit manually")
    w.println("#pragma once")
    w.println()

    w.println("// --- User UART peripheral ---")
    w.println(f"#define UART_TX     (*(volatile uint32_t *)0x${userAddr(USER_PERI_UART, UART_TX_OFFSET)}%08XUL)")
    w.println(f"#define UART_STATUS (*(volatile uint32_t *)0x${userAddr(USER_PERI_UART, UART_STATUS_OFFSET)}%08XUL)")
    w.println(f"#define UART_BAUD   (*(volatile uint32_t *)0x${userAddr(USER_PERI_UART, UART_BAUD_OFFSET)}%08XUL)")
    w.println()

    w.println("// --- Borg GPU peripheral ---")
    val borgBase = userAddr(USER_PERI_BORG, 0)
    w.println(f"#define BORG_BASE    0x${borgBase}%08XUL")
    w.println(f"#define BORG_REG(n)     (*(volatile uint32_t *)(BORG_BASE + (n) * 4))")
    w.println(f"#define BORG_IMEM(n)    (*(volatile uint32_t *)(BORG_BASE + ${BORG_IMEM_OFFSET} + (n) * 4))")
    w.println(f"#define BORG_CONTROL    (*(volatile uint32_t *)(BORG_BASE + ${BORG_CONTROL_OFFSET}))")
    w.println(f"#define BORG_STATUS     (*(volatile uint32_t *)(BORG_BASE + ${BORG_CONTROL_OFFSET}))")
    w.println()

    w.println("// --- PSRAM (QSPI memory space) ---")
    w.println(f"#define PSRAM_IN(n)  (*(volatile uint32_t *)(0x${PSRAM_BASE}%08XUL + (n) * 4))")
    w.println(f"#define PSRAM_OUT(n) (*(volatile uint32_t *)(0x${PSRAM_BASE}%08XUL + ${PSRAM_OUT_OFFSET} + (n) * 4))")
    w.println()

    w.println("// --- Convenience ---")
    w.println("#define STARTUP_DELAY() do { \\")
    w.println("    for (volatile int i = 0; i < 10000; i++) ; \\")
    w.println("  } while (0)")
    w.println()
    w.println("// --- Shared PSRAM layout (matches borg_mmio.py) ---")
    w.println(f"#define TEX_PSRAM_OFFSET $TEX_PSRAM_OFFSET")
    w.println(f"#define DONE_MARKER 0x${DONE_MARKER}%04X")
    w.close()
    println(s"Generated MMIO header: $path")
  }

  /** Emit Python constants file for host scripts. */
  def emitPython(path: String): Unit = {
    val w = new java.io.PrintWriter(path)
    w.println("# Auto-generated by MmioMap.scala — do not edit manually")
    w.println()
    w.println("# PSRAM addresses (QSPI/SPI space)")
    w.println(f"PSRAM_IO_SPI_ADDR = 0x${PSRAM_SPI_BASE}%06X")
    w.println(f"PSRAM_OUT_OFFSET = $PSRAM_OUT_OFFSET")
    w.println()
    w.println("# Shared layout constants")
    w.println(f"TEX_PSRAM_OFFSET = $TEX_PSRAM_OFFSET")
    w.println(f"DONE_MARKER = 0x${DONE_MARKER}%04X")
    w.close()
    println(s"Generated Python constants: $path")
  }
}
