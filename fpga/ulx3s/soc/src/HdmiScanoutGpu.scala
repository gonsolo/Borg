// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._

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

