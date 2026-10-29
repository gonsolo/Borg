// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class SpecialFnIO(cfg: BorgConfig) extends Bundle {
  private val w = cfg.totalBits
  val lutIn   = Input(UInt(w.W))   // the operand a stage before write-back: the tables read it
  val lutFrsq = Input(Bool())      // ... and whether it is FRSQ's
  val in      = Input(UInt(w.W))   // the operand at write-back
  val frcp    = Input(Bool())      // FRCP at write-back
  val frsq    = Input(Bool())      // FRSQ at write-back
  val advance = Input(Bool())
  val out     = Output(UInt(w.W))
}

/** FRCP and FRSQ: a table read a stage before write-back, interpolated by [[Fp16Special]] in FP16.
  * The tables see only the mantissa, scaled into [1, 2) ([2, 4) for FRSQ of an odd exponent, so
  * the halved exponent stays whole); the input's exponent is applied to the result after, so the
  * full FP32 range works (a triangle's setup determinant is often outside FP16's). */
class SpecialFn(cfg: BorgConfig) extends Module {
  val io = IO(new SpecialFnIO(cfg))
  private val rcpRom  = VecInit(BorgLutTables.rcpLut.map(_.U(10.W)))
  private val frsqRom = VecInit(BorgLutTables.frsqLut.map(_.U(10.W)))

  // @doc:frcp
  private def narrowed(a: UInt, frsq: Bool) = {
    val e32 = a(30, 23)
    val eUnb = e32.zext - 127.S                       // the input's exponent, unbiased
    val special32 = e32 === 0.U || e32 === 255.U      // zero or denormal, inf or NaN
    val oddExp = eUnb(0) && frsq
    val scaled = Cat(a(31), Mux(oddExp, 128.U(8.W), 127.U(8.W)), a(22, 0))
    val in = Mux(special32, Fp16Fp32.narrow(a), Fp16Fp32.narrow(scaled))
    (in, eUnb, special32, oddExp)
  }
  private val lutIn = narrowed(io.lutIn, io.lutFrsq)._1
  private val (fp16In, eUnb, special32, oddExp) = narrowed(io.in, io.frsq)
  private val exp  = lutIn(14, 10)
  private val mant = lutIn(9, 0)

  // FRCP's table has 33 entries on mant(9, 5): only idx + 1 reaches the last.
  private val rcpIdx  = mant(9, 5)
  private val rcpVal  = rcpRom(rcpIdx.pad(6))
  private val rcpNext = rcpRom((rcpIdx +& 1.U)(5, 0))
  private val rsqBase = Mux(!exp(0), 17.U(6.W), 0.U(6.W)) + mant(9, 6)
  private val rsqVal  = frsqRom(rsqBase)
  private val rsqNext = frsqRom((rsqBase +& 1.U)(5, 0))
  private val valReg  = RegEnable(Mux(io.lutFrsq, Cat(0.U(6.W), rsqVal), Cat(0.U(6.W), rcpVal)), io.advance)
  private val nextReg = RegEnable(Mux(io.lutFrsq, Cat(0.U(6.W), rsqNext), Cat(0.U(6.W), rcpNext)), io.advance)

  private val special = Module(new Fp16Special)
  special.io.in      := fp16In
  special.io.lutVal  := valReg
  special.io.lutNext := nextReg
  special.io.op      := Mux(io.frcp, Fp16SpecialOp.Rcp, Fp16SpecialOp.Rsq)
  private val r = Fp16Fp32.widen(special.io.out)
  // 1/(m 2^E) = (1/m) 2^-E;  1/sqrt(m 2^E) = (1/sqrt(m')) 2^-(E'/2).
  private val shift = Mux(io.frsq, (eUnb - oddExp.asUInt.zext) >> 1, eUnb)
  private val eOut  = r(30, 23).zext - shift
  private val scaledOut = MuxCase(Cat(r(31), eOut.asUInt(7, 0), r(22, 0)), Seq(
    (eOut <= 0.S)   -> Cat(r(31), 0.U(31.W)),                  // flush to zero
    (eOut >= 255.S) -> Cat(r(31), "hFF".U(8.W), 0.U(23.W))))   // overflow to inf
  io.out := Mux(special32 || r(30, 23) === 255.U, r, scaledOut)
  // @doc:end
}
