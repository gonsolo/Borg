// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class HuttDividerIO(val bits: Int) extends Bundle {
  val start     = Input(Bool())            // pulse to begin; ignored while busy
  val dividend  = Input(UInt(bits.W))      // unsigned magnitude
  val divisor   = Input(UInt(bits.W))      // unsigned magnitude
  val busy      = Output(Bool())
  val done      = Output(Bool())           // one-cycle pulse; quotient/remainder valid
  val quotient  = Output(UInt(bits.W))
  val remainder = Output(UInt(bits.W))
}

/** Unsigned shift-subtract restoring divider, `bits` cycles latency.
  *
  * Operates on magnitudes only — sign handling and RISC-V's divide-by-zero /
  * signed-overflow special cases are the caller's responsibility (see
  * HuttAlu, which wraps this for DIV/DIVU/REM/REMU and the RV64 *W forms).
  *
  * Standard restoring-division algorithm: shift the {remainder, quotient}
  * pair left by one bit each cycle, trial-subtract the divisor from the
  * shifted remainder, and keep the subtraction (quotient bit 1) unless it
  * would go negative, in which case restore the pre-subtraction remainder
  * (quotient bit 0). The remainder register is one bit wider than `bits`
  * so the trial subtraction's sign is unambiguous even when the shifted
  * remainder's value is within one divisor of overflowing a `bits`-wide
  * register.
  *
  * `done` is itself registered (not asserted combinationally off the final
  * iteration's trigger condition) so it lands on the same cycle the
  * `quotient`/`remainder` registers actually hold their final value —
  * those are registers too, so a combinational `done` would pulse one
  * cycle before the results it's supposed to announce are visible.
  */
class HuttDivider(val bits: Int) extends Module {
  val io = IO(new HuttDividerIO(bits))

  val busy    = RegInit(false.B)
  val doneReg = RegInit(false.B)
  val count   = Reg(UInt(log2Ceil(bits).W))
  val divReg  = Reg(UInt(bits.W))
  val rem     = Reg(UInt((bits + 1).W))
  val quo     = Reg(UInt(bits.W))

  io.busy      := busy
  io.done      := doneReg
  io.quotient  := quo
  io.remainder := rem(bits - 1, 0)

  doneReg := false.B

  when(io.start && !busy) {
    busy   := true.B
    count  := (bits - 1).U
    divReg := io.divisor
    rem    := 0.U
    quo    := io.dividend
  }.elsewhen(busy) {
    val shiftedRem = Cat(rem(bits - 1, 0), quo(bits - 1))
    val trial      = shiftedRem - Cat(0.U(1.W), divReg)
    val borrow     = trial(bits)
    rem := Mux(borrow, shiftedRem, trial(bits - 1, 0))
    quo := Cat(quo(bits - 2, 0), !borrow)
    when(count === 0.U) {
      busy    := false.B
      doneReg := true.B
    }.otherwise {
      count := count - 1.U
    }
  }
}
