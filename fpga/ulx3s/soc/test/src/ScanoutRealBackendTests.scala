// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Green-pixel co-sim with the REAL memory stack: HdmiScanoutFp16 fill FSM →
// ULX3S gpuMem arbiter → MemoryController → real SdramBackend → real
// SdramController → SdramChipModel.  Unlike the SdramBackendSim harness (fixed
// latency, clean ready), this exercises the actual variable SDRAM read timing
// (row activate, CAS latency, refresh stalls) — the conditions the on-hardware
// measurement showed are needed to make the fill capture the BZ word into the
// RG slot (Z=0x7bff → green pixel 0).
//
// Acceptance: dbgFill0 / dbgFillSticky shows a NON-gray (green) value written to
// frameBuf[0] ⇒ the bug is reproduced offline, and a fix can be developed here
// without 12-minute resynths.
package soc

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._
import memory.{MemoryController, SdramBackend, SdramChipModel}

class ScanoutRealBackendHarnessIO extends Bundle {
  val run     = Input(Bool())
  val preWe   = Input(Bool())
  val preAddr = Input(UInt(24.W))
  val preData = Input(UInt(16.W))
  // GPU burst-write port (the flusher's path through MemController), used to
  // preload the framebuffer through the REAL write path instead of the backdoor.
  val gwWr     = Input(Bool())
  val gwAddr   = Input(UInt(25.W))
  val gwData   = Input(UInt(32.W))
  val gwLen    = Input(UInt(7.W))
  val gwAccept = Output(Bool())
  val gwReady  = Output(Bool())
  val corrupt = Output(UInt(24.W))   // sticky non-gray fill write to frameBuf[0]
  val fill0    = Output(UInt(24.W))
  val ctrlInit = Output(Bool())      // SDRAM init complete (dqm==0)
}

class ScanoutRealBackendHarness(fbW: Int = 8, fbH: Int = 8, clockMhz: Int = 25,
                                fbBase: Int = 0x1000) extends Module {
  val io = IO(new ScanoutRealBackendHarnessIO)

  val mem     = Module(new MemoryController)
  val backend = Module(new SdramBackend(clockMhz))
  val chip    = Module(new SdramChipModel(addrBits = 22, readLatency = 2))
  val scanout = Module(new HdmiScanoutFp16(fbWidth = fbW, fbHeight = fbH))

  backend.io.backend <> mem.io.backend
  chip.io <> backend.io.sdramPins

  // Preload backdoor.
  chip.dbg.we    := io.preWe
  chip.dbg.waddr := io.preAddr
  chip.dbg.wdata := io.preData
  chip.dbg.raddr := 0.U

  // Tie off CPU/instr ports.
  mem.io.instr.req.valid    := false.B
  mem.io.instr.req.bits     := 0.U
  mem.io.instr.resp.ready   := true.B
  mem.io.cpuData.req.valid  := false.B
  mem.io.cpuData.req.bits   := 0.U.asTypeOf(mem.io.cpuData.req.bits)
  mem.io.cpuData.resp.ready := true.B

  // ── ULX3S gpuMem arbiter (exact copy).  The GPU side carries the flusher's
  // burst writes (io.gw*); gpuActive = the GPU has a pending write/req. ──
  val gpuActive   = io.gwWr
  val scanoutOwns = RegInit(false.B)
  when(scanoutOwns) {
    when(mem.io.gpuMem.ready) { scanoutOwns := false.B }
  }.otherwise {
    when(!gpuActive && scanout.io.gpuReq) { scanoutOwns := true.B }
  }
  val serveGpu = !scanoutOwns
  mem.io.gpuMem.req   := Mux(serveGpu, false.B,     scanout.io.gpuReq)
  mem.io.gpuMem.addr  := Mux(serveGpu, io.gwAddr,   scanout.io.gpuAddr)
  mem.io.gpuMem.wr    := Mux(serveGpu, io.gwWr,     false.B)
  mem.io.gpuMem.wdata := io.gwData
  mem.io.gpuMem.wlen  := Mux(serveGpu, io.gwLen,    1.U)
  scanout.io.gpuData  := mem.io.gpuMem.data
  scanout.io.gpuReady := mem.io.gpuMem.ready && scanoutOwns
  io.gwAccept := mem.io.gpuMem.waccept && serveGpu
  io.gwReady  := mem.io.gpuMem.ready && serveGpu

  scanout.io.hCount   := 0.U
  scanout.io.vCount   := 0.U
  scanout.io.de       := false.B
  scanout.io.tick25   := false.B
  scanout.io.enable   := io.run
  scanout.io.frontBuf := false.B
  scanout.io.fbBase   := fbBase.U
  scanout.io.fbBase1  := fbBase.U

  io.fill0    := scanout.io.dbgFill0
  io.ctrlInit := backend.io.sdramPins.dqm === 0.U

  val corruptReg = RegInit(0.U(24.W))
  when(io.run && scanout.io.dbgFill0 =/= "h333333".U && scanout.io.dbgFill0 =/= 0.U) {
    corruptReg := scanout.io.dbgFill0
  }
  io.corrupt := corruptReg
}

object ScanoutRealBackendTests extends TestSuite {
  // Tiled byte address of pixel (col,row), mirroring HdmiScanoutFp16.
  def pixByteAddr(fbBase: Int, fbW: Int, col: Int, row: Int): Int = {
    val tilesPerRow = fbW / 4
    val tileIndex = (row / 4) * tilesPerRow + (col / 4)
    val pixIndex  = (row % 4) * 4 + (col % 4)
    fbBase + tileIndex * 128 + pixIndex * 8
  }

  val tests = Tests {
    utest.test("green pixel repro through the REAL SdramBackend+SdramController") {
      val fbW = 8; val fbH = 8; val VRAM = 0x1000000
      val MASK = (1 << 22) - 1
      // HW: fbBase1=0xA5604 is NOT 8-byte aligned (bit2 set); fbBase=0x85600 is.
      // Test both an aligned base and a +4-misaligned base.
      // The FLUSHER writes the framebuffer at `flusherBase` (always 8B-aligned);
      // the SCANOUT reads at `scanoutBase`.  On HW these mismatch by +4 for
      // buffer 1 (flusher 0xA5600 vs scanout 0xA5604).
      def runMismatch(flusherBase: Int, scanoutBase: Int): Int = {
      var corrupt = 0
      simulate(new ScanoutRealBackendHarness(fbW = fbW, fbH = fbH, fbBase = scanoutBase)) { dut =>
        dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
        dut.io.run.poke(false.B); dut.io.preWe.poke(false.B)

        // Wait for SDRAM init (INITLEN≈2500 @25MHz + refresh); give margin.
        var n = 0
        while (dut.io.ctrlInit.peek().litValue == 0 && n < 8000) { dut.clock.step(1); n += 1 }
        dut.clock.step(50)

        // Preload a uniform gray framebuffer via the chip backdoor.  The chip is
        // indexed by the controller word address ab = (byteAddr|VRAM) >> 1,
        // masked to the chip's addrBits.  Per pixel: [R,G,B,Z]=[3266,3266,3266,7bff].
        for (row <- 0 until fbH; col <- 0 until fbW) {
          val wbase = ((pixByteAddr(flusherBase, fbW, col, row) | VRAM) >> 1) & MASK
          for (h <- 0 until 4) {
            dut.io.preWe.poke(true.B); dut.io.preAddr.poke((wbase + h).U)
            dut.io.preData.poke((if (h == 3) 0x7bff else 0x3266).U); dut.clock.step(1)
          }
        }
        dut.io.preWe.poke(false.B)

        // Run the fill FSM for many loops.
        dut.io.run.poke(true.B)
        dut.clock.step(60000)
        corrupt = dut.io.corrupt.peek().litValue.toInt & 0xffffff
      }
      corrupt
      }
      // Case 1: flusher and scanout agree (aligned) — expect clean.
      // Case 2: scanout reads +4 past where the flusher wrote (the HW bug).
      val cases = Seq(
        ("match  (0x1000/0x1000)", 0x1000, 0x1000),
        ("+4 skew(0x1000/0x1004)", 0x1000, 0x1004)
      )
      for ((label, fb, sb) <- cases) {
        val c = runMismatch(fb, sb)
        val tag = if (c == 0) "clean (gray)" else f"CORRUPT 0x$c%06x"
        println(f"[real] $label flusher/scanout → frameBuf[0] = $tag")
        if ((sb & 7) != 0 && c != 0) println("[real] ✓✓ REPRODUCED — scanout/flusher +4 mismatch makes pixel 0 green")
      }
    }

    utest.test("write-path: flusher BURST-WRITE then scanout read at same base") {
      // The decisive test: write the framebuffer through the REAL gpuMem burst
      // path (as BorgTileFlusher does) at base B, then read via the scanout at
      // the SAME base B.  If a CONSISTENT misaligned base still goes green, the
      // bug is a write-path/read-path alignment ASYMMETRY in MemController.
      val fbW = 8; val fbH = 8
      def run(base: Int): Int = {
        var corrupt = 0
        simulate(new ScanoutRealBackendHarness(fbW = fbW, fbH = fbH, fbBase = base)) { dut =>
          dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
          dut.io.run.poke(false.B); dut.io.preWe.poke(false.B)
          dut.io.gwWr.poke(false.B); dut.io.gwLen.poke(64.U)
          var n = 0
          while (dut.io.ctrlInit.peek().litValue == 0 && n < 8000) { dut.clock.step(1); n += 1 }
          dut.clock.step(50)

          // Burst one tile (64 halfwords [R,G,B,Z]×16) to byte addr `tileBase`.
          val hw = (0 until 64).map(i => if (i % 4 == 3) 0x7bff else 0x3266)
          def burstTile(tileBase: Int): Unit = {
            dut.io.gwAddr.poke(tileBase.U); dut.io.gwLen.poke(64.U)
            dut.io.gwData.poke(hw(0).U); dut.io.gwWr.poke(true.B)
            var idx = 0; var guard = 0; var done = false
            while (!done && guard < 40000) {
              val acc = dut.io.gwAccept.peek().litValue == 1
              val rdy = dut.io.gwReady.peek().litValue == 1
              dut.clock.step(1)
              if (acc && idx < 63) { idx += 1; dut.io.gwData.poke(hw(idx).U) }
              if (rdy) done = true
              guard += 1
            }
            dut.io.gwWr.poke(false.B); dut.clock.step(3)
          }
          val tilesPerRow = fbW / 4
          val nTiles = (fbW / 4) * (fbH / 4)
          for (t <- 0 until nTiles) burstTile(base + t * 128)

          dut.io.run.poke(true.B)
          dut.clock.step(60000)
          corrupt = dut.io.corrupt.peek().litValue.toInt & 0xffffff
        }
        corrupt
      }
      for (base <- Seq(0x1000, 0x1004)) {
        val c = run(base)
        val al = if ((base & 7) == 0) "8B-aligned" else f"+${base & 7} MISALIGNED"
        val tag = if (c == 0) "clean (gray)" else f"CORRUPT 0x$c%06x"
        println(f"[wpath] base=0x$base%04x ($al%-14s) write+read → frameBuf[0] = $tag")
        if ((base & 7) != 0 && c != 0) println("[wpath] ✓✓ misaligned base reproduces green via the REAL write+read path")
      }
    }
  }
}
