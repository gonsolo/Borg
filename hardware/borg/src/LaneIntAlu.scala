// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** A lane's integer and bit operations on the raw register bits, at write-back. */
object LaneIntAlu {
  /** (result, whether the instruction is one of these) */
  def apply(f: FpuOpFlags, a: UInt, b: UInt, any: Bool, covMask: UInt, dst: Vec[UInt], w: Int): (UInt, Bool) = {
    val shamt = b(log2Ceil(w) - 1, 0)
    def bit(c: Bool) = Mux(c, 1.U(w.W), 0.U(w.W))
    val ops = Seq(
      f.rvx   -> RvInt.exec(f.rvxOp, a, b, w),
      f.iadd  -> (a +& b)(w - 1, 0),
      f.ishl  -> (a << shamt)(w - 1, 0),
      f.ishr  -> (a.asSInt >> shamt).asUInt(w - 1, 0),
      f.imul  -> (a * b)(w - 1, 0),
      f.isub  -> (a -& b)(w - 1, 0),
      f.iand  -> (a & b),
      f.ior   -> (a | b),
      f.ixor  -> (a ^ b),
      f.islt  -> bit(a.asSInt < b.asSInt),
      f.isrl  -> (a >> shamt)(w - 1, 0),
      f.isltu -> bit(a < b),
      f.exany -> bit(any),
      f.smask -> covMask.pad(w),
      f.tld   -> Mux(f.funct3(0), dst(1), dst(0)).pad(w),   // TLD's word: funct3 bit 0
      f.f2h   -> Fp16Fp32.narrow(a).pad(w))
    (MuxCase(Fp16Fp32.widen(a(15, 0)), ops), ops.map(_._1).reduce(_ || _) || f.h2f)
  }
}
