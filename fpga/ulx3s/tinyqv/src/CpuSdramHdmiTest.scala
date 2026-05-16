package soc

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}
import _root_.circt.stage.ChiselStage
import memory._
import tinyqv.cpu.TinyQV

// Scanout engine that uses the GpuMemIO port on MemoryController.
// Reads one 64-pixel line (128 bytes = 32 × 32-bit words) during hblank
// via 32 individual gpuMem read requests.
class HdmiScanoutGpuIO extends Bundle {
  val gpuReq   = Output(Bool())
  val gpuAddr  = Output(UInt(25.W))
  val gpuData  = Input(UInt(32.W))
  val gpuReady = Input(Bool())
  val hCount   = Input(UInt(10.W))
  val vCount   = Input(UInt(10.W))
  val de       = Input(Bool())
  val tick25   = Input(Bool())
  val enable   = Input(Bool())  // gate: don't start until CPU is booted
  val red      = Output(UInt(8.W))
  val green    = Output(UInt(8.W))
  val blue     = Output(UInt(8.W))
  val debug_rdData = Output(UInt(16.W))  // captured line buffer read
}

class HdmiScanoutGpu extends Module {
  val io = IO(new HdmiScanoutGpuIO)

  val fbWidth  = 64.U
  val fbHeight = 64.U
  val startX   = (640.U - fbWidth) / 2.U
  val startY   = (480.U - fbHeight) / 2.U
  val fbBase   = 0x100000.U(25.W)

  val inFbH = io.hCount >= startX && io.hCount < startX + fbWidth
  val inFbV = io.vCount >= startY && io.vCount < startY + fbHeight

  // Line buffer: 64 pixels × 16 bits (RGB565), register-based
  val lineBuffer = RegInit(VecInit(Seq.fill(64)(0.U(16.W))))

  // Which line to prefetch (the NEXT scanline)
  val nextV       = Mux(io.vCount === 524.U, 0.U, io.vCount + 1.U)
  val fetchNextV  = nextV >= startY && nextV < startY + fbHeight
  val triggerFetch = io.tick25 && io.hCount === 640.U && fetchNextV

  // FSM: read 32 words (128 bytes = 64 pixels) via gpuMem
  val sIdle :: sReq :: sWait :: sWrite2 :: Nil = Enum(4)
  val state    = RegInit(sIdle)
  val wordIdx  = RegInit(0.U(6.W)) // 0..31
  val lineAddr = Reg(UInt(25.W))
  val wordBuf  = Reg(UInt(32.W))   // latch 32-bit word for 2nd write

  // Keep gpuReq asserted while waiting — the MemoryController only
  // samples it when !qspi_busy, so a single-cycle pulse would be missed.
  io.gpuReq  := (state === sReq) || (state === sWait)
  io.gpuAddr := lineAddr + Cat(wordIdx, 0.U(2.W))

  when(state === sIdle) {
    when(triggerFetch && io.enable) {
      state    := sReq
      wordIdx  := 0.U
      lineAddr := fbBase + ((nextV - startY) * fbWidth) * 2.U
    }
  } .elsewhen(state === sReq) {
    state := sWait
  } .elsewhen(state === sWait) {
    when(io.gpuReady) {
      // First pixel write + latch word for second
      val pixIdx = (wordIdx << 1.U)(5, 0)
      lineBuffer(pixIdx) := io.gpuData(15, 0)
      wordBuf := io.gpuData
      state   := sWrite2
    }
  } .elsewhen(state === sWrite2) {
    // Second pixel write (from latched word)
    val pixIdx = (wordIdx << 1.U)(5, 0)
    lineBuffer(pixIdx + 1.U) := wordBuf(31, 16)
    when(wordIdx === 31.U) {
      state := sIdle
    } .otherwise {
      wordIdx := wordIdx + 1.U
      state   := sReq
    }
  }

  // Read pixel from line buffer during active display
  val rdAddr = (io.hCount - startX)(5, 0)  // clamp to 6 bits for 64-entry Vec
  val rdData = lineBuffer(rdAddr)

  val r5 = rdData(15, 11)
  val g6 = rdData(10, 5)
  val b5 = rdData(4, 0)

  val r8 = Cat(r5, r5(4, 2))
  val g8 = Cat(g6, g6(5, 4))
  val b8 = Cat(b5, b5(4, 2))

  // Use undelayed signals — all entries are identical so the 1-cycle
  // SyncReadMem latency just shifts WHICH entry we read, not the value.
  io.red   := Mux(io.de && inFbH && inFbV, r8, 0.U)
  io.green := Mux(io.de && inFbH && inFbV, g8, 0.U)
  io.blue  := Mux(io.de && inFbH && inFbV, b8, 0.U)

  // DEBUG: capture pixel 32 from the green area
  val capturedRd  = RegInit(0.U(16.W))
  val capturedAny = RegInit(false.B)
  val pixCount    = RegInit(0.U(7.W))
  when(io.de && inFbH && inFbV) {
    pixCount := pixCount + 1.U
    when(pixCount === 32.U && !capturedAny) {
      capturedRd  := rdData
      capturedAny := true.B
    }
  }
  io.debug_rdData := capturedRd
}

class CpuSdramHdmiTest(clockMhz: Int = 50) extends RawModule {
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
  val gpdi_dp    = IO(Output(UInt(4.W)))
  val ftdi_rxd   = IO(Output(Bool()))
  val led        = IO(Output(UInt(8.W)))

  // ── PLL: out0=125MHz (TMDS), out1=25MHz+90° (SDRAM), out2=25MHz (system) ──
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = 125_000_000L,                    // TMDS serializer clock
    out1Hz = 50_000_000L, out1Deg = 90,       // SDRAM clock (phase-shifted)
    out2Hz = 50_000_000L                      // system clock
  )))
  pll.io.clk_i := clk_25mhz
  val pllLocked = pll.io.locked
  val tmdsClock = pll.io.clk_o(0)   // 125 MHz for TMDS serializers
  sdram_clk    := pll.io.clk_o(1)   // 25 MHz + 90°
  val sysClock  = pll.io.clk_o(2)   // 25 MHz system clock
  val pllRst    = !pllLocked

  // ── Flash Boot Loader ──
  val flashBoot = withClockAndReset(sysClock, pllRst) { Module(new FlashBootLoader()) }
  val usrmclk   = Module(new Usrmclk)
  usrmclk.USRMCLKI  := flashBoot.io.spi_clk.asClock
  usrmclk.USRMCLKTS := false.B
  flash_csn  := flashBoot.io.flash_csn
  flash_mosi := flashBoot.io.flash_mosi
  flashBoot.io.flash_miso := flash_miso

  // ── SDRAM Backend ──
  val sdramBackend = withClockAndReset(sysClock, pllRst) { Module(new SdramBackend(clockMhz)) }
  val bootDone     = flashBoot.io.boot_done

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

  // ── CPU Reset ──
  val cpuRst_n = pllLocked && bootDone && rst_n
  val cpuRst_reg_n = withClockAndReset(sysClock, !cpuRst_n) {
    val sync = RegInit(0.U(4.W))
    sync := Cat(sync(2, 0), true.B)
    sync(3)
  }

  // ── TinyQV CPU ──
  val cpu = withClockAndReset(sysClock, !cpuRst_reg_n) { Module(new TinyQV()) }

  // ── Memory Controller ──
  val mem = withClockAndReset(sysClock, !cpuRst_reg_n) { Module(new MemoryController()) }

  // Wire CPU ↔ MemoryController
  // Disable instruction fetches ~100ms after boot (CPU finishes framebuffer fill)
  val cpuDone = withClockAndReset(sysClock, pllRst) {
    val ctr = RegInit(0.U(23.W))  // ~100ms at 50 MHz
    when(bootDone && !ctr.andR) { ctr := ctr + 1.U }
    ctr.andR
  }
  mem.io.instrFetch.instr_addr          := cpu.io.instr_addr
  mem.io.instrFetch.instr_fetch_restart := cpu.io.instr_fetch_restart && !cpuDone
  mem.io.instrFetch.instr_fetch_stall   := cpu.io.instr_fetch_stall

  // Explicit CPU data bus wiring — force writeN/readN to 3 after cpuDone
  // to prevent stale CPU state from blocking GPU writes in the arbiter
  mem.io.cpuData.addr        := cpu.io.memBus.addr
  mem.io.cpuData.dataOut     := cpu.io.memBus.dataOut
  mem.io.cpuData.writeN      := Mux(cpuDone, 3.U, cpu.io.memBus.writeN)
  mem.io.cpuData.readN       := Mux(cpuDone, 3.U, cpu.io.memBus.readN)
  mem.io.cpuData.dataContinue := cpu.io.memBus.dataContinue
  cpu.io.memBus.ready        := mem.io.cpuData.ready
  cpu.io.memBus.dataIn       := mem.io.cpuData.dataIn
  cpu.io.instr_fetch_started := mem.io.instrFetch.instr_fetch_started
  cpu.io.instr_fetch_stopped := mem.io.instrFetch.instr_fetch_stopped
  cpu.io.instr_data          := mem.io.instrFetch.instr_data
  cpu.io.instr_ready         := mem.io.instrFetch.instr_ready

  // ── CPU data bus: tie off (no Peripherals in minimal test) ──
  cpu.io.data_ready    := true.B
  cpu.io.data_in       := 0.U
  cpu.io.interrupt_req := 0.U
  cpu.io.time_pulse    := false.B
  // ── HDMI Scanout ──
  val scanout = withClockAndReset(sysClock, pllRst) { Module(new HdmiScanoutGpu) }

  // ── Framebuffer fill FSM (GPU write port, bypasses CPU) ──
  // Writes 0xF800 (red) to all 64×64 pixels, one 16-bit pixel per SDRAM write.
  val fbBase    = 0x100000
  val fbPixels  = 64 * 64  // 4096 pixels

  val fbFillDone = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }
  val fbFillIdx  = withClockAndReset(sysClock, pllRst) { RegInit(0.U(13.W)) }  // 0..4095

  withClockAndReset(sysClock, pllRst) {
    when(cpuDone && !fbFillDone && mem.io.gpuMem.ready) {
      when(fbFillIdx === (fbPixels - 1).U) {
        fbFillDone := true.B
      } .otherwise {
        fbFillIdx := fbFillIdx + 1.U
      }
    }
  }

  // ── Scanout: reconnect through MemoryController ──
  // During fill: GPU write port drives addr/wr/wdata; scanout disabled
  // After fill: scanout drives addr/req for reads
  val filling = cpuDone && !fbFillDone
  mem.io.gpuMem.req   := Mux(filling, false.B, scanout.io.gpuReq)
  mem.io.gpuMem.addr  := Mux(filling, fbBase.U(25.W) + Cat(fbFillIdx, 0.U(1.W)), scanout.io.gpuAddr)
  mem.io.gpuMem.wr    := filling
  mem.io.gpuMem.wdata := 0xF800.U  // red pixel (lower 16 bits only, data_txn_len=1)
  scanout.io.gpuData  := mem.io.gpuMem.data
  scanout.io.gpuReady := Mux(filling, false.B, mem.io.gpuMem.ready)
  scanout.io.enable   := fbFillDone

  // ── Backend mux: FlashBootLoader during boot, MemoryController after ──
  sdramBackend.io.backend.addrIn     := Mux(bootDone, mem.io.backend.addrIn,     flashBoot.io.backend.addrIn)
  sdramBackend.io.backend.dataIn     := Mux(bootDone, mem.io.backend.dataIn,     flashBoot.io.backend.dataIn)
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead,  false.B)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
  sdramBackend.io.backend.stallTxn   := Mux(bootDone, mem.io.backend.stallTxn,   false.B)
  sdramBackend.io.backend.stopTxn    := Mux(bootDone, mem.io.backend.stopTxn,    false.B)

  mem.io.backend.dataOut   := Mux(bootDone, sdramBackend.io.backend.dataOut,   0.U)
  mem.io.backend.dataReq   := Mux(bootDone, sdramBackend.io.backend.dataReq,   false.B)
  mem.io.backend.dataReady := Mux(bootDone, sdramBackend.io.backend.dataReady, false.B)
  mem.io.backend.busy      := Mux(bootDone, sdramBackend.io.backend.busy,      false.B)

  flashBoot.io.backend.dataOut   := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.dataReq   := Mux(!bootDone, sdramBackend.io.backend.dataReq,   false.B)
  flashBoot.io.backend.dataReady := Mux(!bootDone, sdramBackend.io.backend.dataReady, false.B)
  flashBoot.io.backend.busy      := Mux(!bootDone, sdramBackend.io.backend.busy,      false.B)

  // ── VGA timing (25 MHz pixel clock from 50 MHz / 2) ──
  val count = withClockAndReset(sysClock, pllRst) { RegInit(0.U(1.W)) }
  val tick25 = (count === 1.U)
  withClockAndReset(sysClock, pllRst) {
    count := ~count
  }

  val hCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }
  val vCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }

  val hTotal  = 800.U;  val vTotal  = 525.U
  val hActive = 640.U;  val vActive = 480.U
  val hFront  = 16.U;   val hSync   = 96.U
  val vFront  = 10.U;   val vSync   = 2.U

  withClockAndReset(sysClock, pllRst) {
    when(tick25) {
      when(hCount === hTotal - 1.U) {
        hCount := 0.U
        when(vCount === vTotal - 1.U) { vCount := 0.U }
        .otherwise { vCount := vCount + 1.U }
      } .otherwise {
        hCount := hCount + 1.U
      }
    }
  }

  val de    = (hCount < hActive) && (vCount < vActive)
  val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync))
  val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync))

  scanout.io.hCount := hCount
  scanout.io.vCount := vCount
  scanout.io.de     := de
  scanout.io.tick25 := tick25

  // ── TMDS Encoders (50 MHz domain) ──
  val encB = withClockAndReset(sysClock, pllRst) { Module(new TmdsEncoder) }
  encB.io.en := tick25; encB.io.data := scanout.io.blue
  encB.io.c  := Cat(vsync, hsync); encB.io.de := de

  val encG = withClockAndReset(sysClock, pllRst) { Module(new TmdsEncoder) }
  encG.io.en := tick25; encG.io.data := scanout.io.green
  encG.io.c  := 0.U; encG.io.de := de

  val encR = withClockAndReset(sysClock, pllRst) { Module(new TmdsEncoder) }
  encR.io.en := tick25; encR.io.data := scanout.io.red
  encR.io.c  := 0.U; encR.io.de := de

  // ── TMDS Serializers (125 MHz domain — 5:1 serialization) ──
  // tick25 in 125 MHz domain: reload every 5th cycle
  val tmdsCount = withClockAndReset(tmdsClock, pllRst) { RegInit(0.U(3.W)) }
  val tmdsTick25 = (tmdsCount === 4.U)
  withClockAndReset(tmdsClock, pllRst) {
    when(tmdsTick25) { tmdsCount := 0.U } .otherwise { tmdsCount := tmdsCount + 1.U }
  }

  val serB = withClockAndReset(tmdsClock, pllRst) { Module(new TmdsSerializer) }
  serB.io.en := tmdsTick25; serB.io.tmds := encB.io.tmds

  val serG = withClockAndReset(tmdsClock, pllRst) { Module(new TmdsSerializer) }
  serG.io.en := tmdsTick25; serG.io.tmds := encG.io.tmds

  val serR = withClockAndReset(tmdsClock, pllRst) { Module(new TmdsSerializer) }
  serR.io.en := tmdsTick25; serR.io.tmds := encR.io.tmds

  val serClk = withClockAndReset(tmdsClock, pllRst) { Module(new TmdsSerializer) }
  serClk.io.en := tmdsTick25; serClk.io.tmds := "b0000011111".U

  gpdi_dp  := Cat(serClk.io.out, serR.io.out, serG.io.out, serB.io.out)
  ftdi_rxd := false.B
  // ── Diagnostic: capture first GPU read word after fill ──
  val readCount  = withClockAndReset(sysClock, pllRst) { RegInit(0.U(8.W)) }
  val gpuWord0   = withClockAndReset(sysClock, pllRst) { RegInit(0.U(32.W)) }
  withClockAndReset(sysClock, pllRst) {
    when(fbFillDone && mem.io.gpuMem.ready && readCount < 1.U) {
      gpuWord0  := mem.io.gpuMem.data
      readCount := readCount + 1.U
    }
  }
  // LED7=fbFillDone, LED[6:3]=gpuWord0(15:12), LED[2:0]=gpuWord0(10:8)
  led := Cat(fbFillDone, gpuWord0(15, 12), gpuWord0(10, 8))
}

object CpuSdramHdmiTestMain extends App {
  val targetDir = sys.env.getOrElse("TARGET_DIR", "out/ulx3s/cpu_sdram_hdmi_test")
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new CpuSdramHdmiTest(clockMhz = 50),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
