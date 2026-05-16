// HdmiScanoutTiled — DIAGNOSTIC: Full-screen test + SDRAM overlay
//
// Full-screen coordinate-based test pattern (proven working).
// Center 64×64 region reads RGB565 from SDRAM via gpuMem.
// If the test pattern is clean but the center is garbled → SDRAM bug.

package soc

import chisel3._
import chisel3.util._

class HdmiScanoutTiledIO extends Bundle {
  val gpuReq   = Output(Bool())
  val gpuAddr  = Output(UInt(25.W))
  val gpuData  = Input(UInt(32.W))
  val gpuReady = Input(Bool())
  val hCount   = Input(UInt(10.W))
  val vCount   = Input(UInt(10.W))
  val de       = Input(Bool())
  val tick25   = Input(Bool())
  val enable   = Input(Bool())
  val red      = Output(UInt(8.W))
  val green    = Output(UInt(8.W))
  val blue     = Output(UInt(8.W))
}

class HdmiScanoutTiled extends Module {
  val io = IO(new HdmiScanoutTiledIO)

  // ══════════════════════════════════════════════════════════
  // SDRAM overlay: 64×64 RGB565 rectangle in center
  // ══════════════════════════════════════════════════════════
  val fbBase  = 0x100000.U(25.W)  // proven working SDRAM address
  val fbWidth = 64.U(10.W)
  val startX  = ((640 - 64) / 2).U(10.W)  // 288
  val startY  = ((480 - 64) / 2).U(10.W)  // 208
  val endX    = startX +& fbWidth          // 352
  val endY    = startY +& 64.U             // 272

  val inFbH = (io.hCount >= startX) && (io.hCount < endX)
  val inFbV = (io.vCount >= startY) && (io.vCount < endY)

  // Line buffer for one 64-pixel scanline
  val lineBuffer = SyncReadMem(64, UInt(16.W))

  // Fetch next line during hblank
  val nextV = Mux(io.vCount >= endY - 1.U, startY, io.vCount + 1.U)
  val fetchNextV = (nextV >= startY) && (nextV < endY)
  val triggerFetch = io.tick25 && (io.hCount === 640.U) && fetchNextV

  val sIdle :: sReq :: sWait :: sWrite2 :: Nil = Enum(4)
  val state    = RegInit(sIdle)
  val wordIdx  = RegInit(0.U(6.W))
  val lineAddr = Reg(UInt(25.W))
  val wordBuf  = Reg(UInt(32.W))

  io.gpuReq  := (state === sReq) || (state === sWait)
  io.gpuAddr := lineAddr +& (wordIdx << 2)

  when(state === sIdle) {
    when(triggerFetch && io.enable) {
      state    := sReq
      wordIdx  := 0.U
      lineAddr := fbBase +& ((nextV - startY) * fbWidth * 2.U)
    }
  } .elsewhen(state === sReq) {
    state := sWait
  } .elsewhen(state === sWait) {
    when(io.gpuReady) {
      val pixBase = wordIdx << 1
      lineBuffer.write(pixBase, io.gpuData(15, 0))
      wordBuf := io.gpuData
      state   := sWrite2
    }
  } .elsewhen(state === sWrite2) {
    val pixBase = wordIdx << 1
    lineBuffer.write(pixBase +& 1.U, wordBuf(31, 16))
    when(wordIdx === 31.U) {
      state := sIdle
    } .otherwise {
      wordIdx := wordIdx + 1.U
      state   := sReq
    }
  }

  // Read from line buffer for overlay
  val rdAddr = io.hCount - startX
  val rdData = lineBuffer.read(rdAddr)
  val r5 = rdData(15, 11); val g6 = rdData(10, 5); val b5 = rdData(4, 0)
  val r8_fb = Cat(r5, r5(4, 2))
  val g8_fb = Cat(g6, g6(5, 4))
  val b8_fb = Cat(b5, b5(4, 2))

  // ══════════════════════════════════════════════════════════
  // Full-screen test pattern (coordinate-based, no SDRAM)
  // ══════════════════════════════════════════════════════════
  val x = io.hCount
  val y = io.vCount

  val r_pat = WireDefault(0.U(8.W))
  val g_pat = WireDefault(0.U(8.W))
  val b_pat = WireDefault(0.U(8.W))

  when(y < 120.U) {
    // 8 vertical color bars
    val barNum = Wire(UInt(3.W))
    barNum := Mux(x < 80.U, 0.U,
              Mux(x < 160.U, 1.U,
              Mux(x < 240.U, 2.U,
              Mux(x < 320.U, 3.U,
              Mux(x < 400.U, 4.U,
              Mux(x < 480.U, 5.U,
              Mux(x < 560.U, 6.U,
                             7.U)))))))
    val barColors = VecInit(Seq(
      "b111".U(3.W), "b110".U(3.W), "b011".U(3.W), "b010".U(3.W),
      "b101".U(3.W), "b100".U(3.W), "b001".U(3.W), "b000".U(3.W)
    ))
    val bar = barColors(barNum)
    r_pat := Mux(bar(2), 255.U, 0.U)
    g_pat := Mux(bar(1), 255.U, 0.U)
    b_pat := Mux(bar(0), 255.U, 0.U)
  } .elsewhen(y < 240.U) {
    r_pat := (x >> 1)(7, 0)
  } .elsewhen(y < 360.U) {
    g_pat := (x >> 1)(7, 0)
  } .otherwise {
    val white = x(4) ^ y(4)
    r_pat := Mux(white, 255.U, 0.U)
    g_pat := Mux(white, 255.U, 0.U)
    b_pat := Mux(white, 255.U, 0.U)
  }

  // ══════════════════════════════════════════════════════════
  // Output mux: SDRAM overlay in center, test pattern elsewhere
  // ══════════════════════════════════════════════════════════
  val useOverlay = inFbH && inFbV

  io.red   := Mux(io.de, Mux(useOverlay, r8_fb, r_pat), 0.U)
  io.green := Mux(io.de, Mux(useOverlay, g8_fb, g_pat), 0.U)
  io.blue  := Mux(io.de, Mux(useOverlay, b8_fb, b_pat), 0.U)
}
