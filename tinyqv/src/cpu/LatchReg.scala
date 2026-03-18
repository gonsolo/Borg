// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

// A wrapper to use a latch as a register
// Note no reset - reset using data

class LatchRegIO(val w: Int) extends Bundle {
  val wen = Input(Bool())
  val data_in = Input(UInt(w.W))
  val data_out = Output(UInt(w.W))
}

class LatchReg32IO extends Bundle {
  val wen = Input(Bool())
  val data_in = Input(UInt(32.W))
  val data_out = Output(UInt(32.W))
}

trait HasLatchRegIO { this: Module =>
  val io: LatchRegIO
}

class LatchRegN(w: Int) extends Module with HasLatchRegIO {
  val io = IO(new LatchRegIO(w))

  // Negative edge register
  val state = withClock((!clock.asBool).asClock) {
    RegEnable(io.data_in, io.wen)
  }
  io.data_out := state
}

class LatchRegP(w: Int) extends Module with HasLatchRegIO {
  val io = IO(new LatchRegIO(w))

  // Positive edge register
  val state = RegEnable(io.data_in, io.wen)
  io.data_out := state
}

class LatchReg32(gen: => Module with HasLatchRegIO) extends Module {
  val io = IO(new LatchReg32IO)

  val l_lo = Module(gen)
  val l_hi = Module(gen)

  l_lo.io.wen := io.wen
  l_lo.io.data_in := io.data_in(15, 0)
  
  l_hi.io.wen := io.wen
  l_hi.io.data_in := io.data_in(31, 16)

  io.data_out := Cat(l_hi.io.data_out, l_lo.io.data_out)
}

class LatchReg32N extends LatchReg32(new LatchRegN(16))
class LatchReg32P extends LatchReg32(new LatchRegP(16))

