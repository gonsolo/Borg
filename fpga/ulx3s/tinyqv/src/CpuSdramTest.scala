// CpuSdramTest.scala — Minimal CPU + SDRAM debug harness for fast iteration.
//
// Exercises the MemoryController + SdramBackend read path post-boot without
// the full SoC (no GPU, no Peripherals, no Borg). Builds in ~6s.
//
// LED map:
//   [7] = PLL locked
//   [6] = boot_done
//   [5:3] = SdramController FSM state (0=IDLE,1=RFRSH1,2=RFRSH2,3=CONFIG,
//                                       4=RDWR,5=RWRDY,6=ACKWT,7=WAIT)
//   [2:0] = SdramBackend FSM state (low 3 bits)
//           0=Idle,1=RdReq,2=RdWait,3=RdAck,4=ReadB,5=WrReq,...

package soc

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}
import _root_.circt.stage.ChiselStage
import memory._
import tinyqv.cpu.TinyQV

// ─────────────────────────────────────────────────────────────────────────────

class CpuSdramTest(clockMhz: Int = 125) extends RawModule {
  // ── IOs (same pin set as BootUartTest) ──
  val clk_25mhz  = IO(Input(Clock()))
  val rst_n      = IO(Input(Bool()))
  val flash_csn  = IO(Output(Bool()))
  val flash_mosi = IO(Output(Bool()))
  val flash_miso = IO(Input(Bool()))
  val sdram_clk  = IO(Output(Clock()))
  val sdram_cke  = IO(Output(Bool()))
  val sdram_csn  = IO(Output(Bool()))
  val sdram_wen  = IO(Output(Bool()))
  val sdram_rasn = IO(Output(Bool()))
  val sdram_casn = IO(Output(Bool()))
  val sdram_a    = IO(Output(UInt(13.W)))
  val sdram_ba   = IO(Output(UInt(2.W)))
  val sdram_dqm  = IO(Output(UInt(2.W)))
  val sdram_d    = IO(Vec(16, Analog(1.W)))
  val ftdi_rxd   = IO(Output(Bool()))
  val led        = IO(Output(UInt(8.W)))

  // ── PLL ──────────────────────────────────────────────────────────────────
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = clockMhz.toLong * 1_000_000L,
    out1Hz = clockMhz.toLong * 1_000_000L, out1Deg = 90
  )))
  pll.io.clk_i  := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)
  sdram_clk     := pll.io.clk_o(1)
  val pllRst     = !pllLocked

  // ── FlashBootLoader ──────────────────────────────────────────────────────
  val flashBoot = withClockAndReset(sysClock, pllRst) { Module(new FlashBootLoader()) }
  val usrmclk   = Module(new Usrmclk)
  usrmclk.USRMCLKI  := flashBoot.io.spi_clk.asClock
  usrmclk.USRMCLKTS := false.B
  flash_csn  := flashBoot.io.flash_csn
  flash_mosi := flashBoot.io.flash_mosi
  flashBoot.io.flash_miso := flash_miso

  // ── SdramBackend ─────────────────────────────────────────────────────────
  val sdramBackend = withClockAndReset(sysClock, pllRst) { Module(new SdramBackend(clockMhz)) }
  val bootDone = flashBoot.io.boot_done

  // ── SDRAM physical pins ───────────────────────────────────────────────────
  val pins = sdramBackend.io.sdramPins
  sdram_cke  := pins.cke
  sdram_csn  := pins.cs_n
  sdram_wen  := pins.we_n
  sdram_rasn := pins.ras_n
  sdram_casn := pins.cas_n
  sdram_a    := pins.addr
  sdram_ba   := pins.ba
  sdram_dqm  := pins.dqm
  val dqIn = Wire(Vec(16, Bool()))
  for (i <- 0 until 16) {
    val bb = Module(new Ecp5BiDirBuf())
    bb.T := !pins.dq_oe
    bb.I := pins.dq_out(i)
    dqIn(i) := bb.O
    attach(sdram_d(i), bb.B)
  }
  pins.dq_in := dqIn.asUInt

  // ── CPU reset: held until boot_done, with proper synchronizer ──────────
  // Old: single RegNext with false.B reset — vulnerable to glitches on
  // bootDone or rst_n.  New: 4-stage shift register that requires 4
  // consecutive cycles of cpuRst_n=high before releasing reset.  Any
  // single-cycle glitch immediately re-asserts reset via async clear.
  val cpuRst_n = pllLocked && bootDone && rst_n
  val cpuRst_reg_n = withClockAndReset(sysClock, !cpuRst_n) {
    val sync = RegInit(0.U(4.W))
    sync := Cat(sync(2, 0), true.B)
    sync(3)  // only release after 4 consecutive cycles of cpuRst_n=high
  }

  // ── TinyQV CPU ───────────────────────────────────────────────────────────
  val cpu = withClockAndReset(sysClock, !cpuRst_reg_n) { Module(new TinyQV()) }

  // ── MemoryController ─────────────────────────────────────────────────────
  val mem = withClockAndReset(sysClock, !cpuRst_reg_n) { Module(new MemoryController()) }

  // Wire CPU ↔ MemoryController (same as wireSoC in Project.scala)
  mem.io.instrFetch.instr_addr          := cpu.io.instr_addr
  mem.io.instrFetch.instr_fetch_restart := cpu.io.instr_fetch_restart
  mem.io.instrFetch.instr_fetch_stall   := cpu.io.instr_fetch_stall
  mem.io.cpuData                        <> cpu.io.memBus
  cpu.io.instr_fetch_started := mem.io.instrFetch.instr_fetch_started
  cpu.io.instr_fetch_stopped := mem.io.instrFetch.instr_fetch_stopped
  cpu.io.instr_data          := mem.io.instrFetch.instr_data
  cpu.io.instr_ready         := mem.io.instrFetch.instr_ready

  // Tie off GPU memory port (not used)
  mem.io.gpuMem.req   := false.B
  mem.io.gpuMem.wr    := false.B
  mem.io.gpuMem.addr  := 0.U
  mem.io.gpuMem.wdata := 0.U

  // Tie off CPU interrupt / time inputs
  cpu.io.interrupt_req := 0.U
  cpu.io.time_pulse    := false.B

  // Minimal MMIO: all peripheral reads return 0, writes ignored
  // (The debug UART at 0x08000018 will write → PERI_DEBUG_UART path in full SoC,
  //  but here we just need the CPU to successfully fetch and execute)
  cpu.io.data_ready := true.B   // always ready (no real peripherals)
  cpu.io.data_in    := 0.U

  // ── Backend mux: FlashBootLoader during boot, MemoryController after ─────
  sdramBackend.io.backend.addrIn     := Mux(bootDone, mem.io.backend.addrIn,    flashBoot.io.backend.addrIn)
  sdramBackend.io.backend.dataIn     := Mux(bootDone, mem.io.backend.dataIn,    flashBoot.io.backend.dataIn)
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead, false.B)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
  sdramBackend.io.backend.stallTxn   := Mux(bootDone, mem.io.backend.stallTxn, false.B)
  sdramBackend.io.backend.stopTxn    := Mux(bootDone, mem.io.backend.stopTxn,  false.B)

  mem.io.backend.dataOut   := Mux(bootDone, sdramBackend.io.backend.dataOut,   0.U)
  mem.io.backend.dataReq   := Mux(bootDone, sdramBackend.io.backend.dataReq,   false.B)
  mem.io.backend.dataReady := Mux(bootDone, sdramBackend.io.backend.dataReady, false.B)
  mem.io.backend.busy      := Mux(bootDone, sdramBackend.io.backend.busy,      false.B)

  flashBoot.io.backend.dataOut   := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.dataReq   := Mux(!bootDone, sdramBackend.io.backend.dataReq,   false.B)
  flashBoot.io.backend.dataReady := false.B
  flashBoot.io.backend.busy      := Mux(!bootDone, sdramBackend.io.backend.busy,       false.B)

  // ── Debug UART: replicate SoCDecode from Project.scala ────────────────────
  // SoC region: Cat(addr[27:6], addr[1:0]) == 0x800000; index = addr[5:2]
  // PERI_DEBUG_UART = 0x6 → addr[5:2] = 6
  val addr    = cpu.io.data_addr   // 28-bit
  val write_n = cpu.io.data_write_n
  val data_out = cpu.io.data_out
  val isSocRegion       = Cat(addr(27, 6), addr(1, 0)) === "h800000".U
  val periIndex         = addr(5, 2)
  val isDebugUartWrite  = isSocRegion && (periIndex === 6.U) && (write_n =/= 3.U)

  // ── Inline bit-bang UART TX (gap-counter) ─────────────────────────────────
  // Proven working on ECP5. Sends last latched byte on each gap-timer expiry.
  // 13-bit gap ≈ 65 µs, plus 87 µs TX = ~152 µs per character ≈ 6600 char/s.
  val CLKS_PER_BIT = (clockMhz * 1000000 / 115200)

  val txOut = withClockAndReset(sysClock, pllRst) {
    val idle    = RegInit(true.B)
    val shift   = RegInit(0xFF.U(8.W))
    val bitIdx  = RegInit(0.U(4.W))
    val baudCtr = RegInit(0.U(11.W))
    val out     = RegInit(true.B)
    val gapCtr  = RegInit(0.U(13.W))
    val inGap   = RegInit(true.B)
    val lastByte = RegInit(0x41.U(8.W))

    // Latch CPU write data
    when(isDebugUartWrite) {
      lastByte := data_out(7, 0)
    }

    when(inGap) {
      gapCtr := gapCtr + 1.U
      when(gapCtr.andR) {
        inGap   := false.B
        idle    := false.B
        shift   := lastByte
        bitIdx  := 0.U
        baudCtr := 0.U
        out     := false.B  // start bit
      }
    }.otherwise {
      when(idle) {
        inGap  := true.B
        gapCtr := 0.U
      }.otherwise {
        when(baudCtr === (CLKS_PER_BIT - 1).U) {
          baudCtr := 0.U
          when(bitIdx === 9.U) {
            idle := true.B
            out  := true.B
          }.elsewhen(bitIdx === 0.U) {
            out    := shift(0)
            shift  := Cat(true.B, shift(7, 1))
            bitIdx := 1.U
          }.elsewhen(bitIdx <= 7.U) {
            out    := shift(0)
            shift  := Cat(true.B, shift(7, 1))
            bitIdx := bitIdx + 1.U
          }.otherwise {
            out    := true.B
            bitIdx := 9.U
          }
        }.otherwise {
          baudCtr := baudCtr + 1.U
        }
      }
    }
    out
  }

  ftdi_rxd := txOut

  // ── LEDs ─────────────────────────────────────────────────────────────────
  // [7]=pll [6]=boot_done [5]=cpuRst_reg_n [4]=instrComplete_ever
  // [3]=write_n_not3_ever [2]=isSocRegion_during_write [1]=isDebugUartWrite_ever [0]=instrReady_ever
  val instrCompleteSeen = withClockAndReset(sysClock, pllRst) {
    val seen = RegInit(false.B)
    when(cpu.io.debug_instr_complete) { seen := true.B }
    seen
  }
  // Has write_n from TinyQV MMIO port EVER gone non-3?
  val writeNotThreeSeen = withClockAndReset(sysClock, pllRst) {
    val seen = RegInit(false.B)
    when(write_n =/= 3.U) { seen := true.B }
    seen
  }
  // Was isSocRegion true during ANY write_n =/= 3?
  val socRegionWriteSeen = withClockAndReset(sysClock, pllRst) {
    val seen = RegInit(false.B)
    when(isSocRegion && write_n =/= 3.U) { seen := true.B }
    seen
  }
  // Full isDebugUartWrite ever?
  val uartWriteSeen = withClockAndReset(sysClock, pllRst) {
    val seen = RegInit(false.B)
    when(isDebugUartWrite) { seen := true.B }
    seen
  }
  val instrReadySeen = withClockAndReset(sysClock, pllRst) {
    val seen = RegInit(false.B)
    when(cpu.io.instr_ready) { seen := true.B }
    seen
  }
  led := Cat(pllLocked, bootDone, cpuRst_reg_n, instrCompleteSeen, writeNotThreeSeen,
             socRegionWriteSeen, uartWriteSeen, instrReadySeen)
}

// ── Emit ──────────────────────────────────────────────────────────────────────
object CpuSdramTestMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/cpu_sdram_test/verilog"
  new java.io.File(targetDir).mkdirs()
  ChiselStage.emitSystemVerilogFile(
    gen         = new CpuSdramTest(clockMhz),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
