// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package riscv

import chisel3._
import chisel3.util._

/** RISC-V base pieces Hutt and the Borg shader processor share, as pure functions of width `w`. */
object Rv {
  def immI(instr: UInt, w: Int): UInt = instr(31, 20).asSInt.pad(w).asUInt
  def immS(instr: UInt, w: Int): UInt = Cat(instr(31, 25), instr(11, 7)).asSInt.pad(w).asUInt
  def immB(instr: UInt, w: Int): UInt =
    Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W)).asSInt.pad(w).asUInt
  def immU(instr: UInt, w: Int): UInt = Cat(instr(31, 12), 0.U(12.W)).asSInt.pad(w).asUInt
  def immJ(instr: UInt, w: Int): UInt =
    Cat(instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W)).asSInt.pad(w).asUInt

  /** BEQ..BGEU by funct3; funct3 2 and 3 are never taken. */
  def branchTaken(funct3: UInt, a: UInt, b: UInt): Bool = MuxLookup(funct3, false.B)(Seq(
    0.U -> (a === b), 1.U -> (a =/= b), 4.U -> (a.asSInt < b.asSInt), 5.U -> (a.asSInt >= b.asSInt),
    6.U -> (a < b), 7.U -> (a >= b)))

  /** LB..LWU by funct3 on `raw`, the loaded bits already at bit 0; a load as wide as `w` gives
    * `full` (default `raw`). */
  def loadExtend(funct3: UInt, raw: UInt, w: Int, full: Option[UInt] = None): UInt =
    MuxLookup(funct3, full.getOrElse(raw(w - 1, 0)))(Seq((0, 8, true), (1, 16, true), (2, 32, true),
      (4, 8, false), (5, 16, false), (6, 32, false)).filter(_._2 < w).map { case (f, from, signed) =>
      val v = raw(from - 1, 0)
      f.U -> (if (signed) v.asSInt.pad(w).asUInt else v.pad(w))
    })

  /** The value an AMO stores by funct5, from the loaded `old` and the operand `x`; AMOCAS.W (0x05)
    * compares with `cmp` when given. Other funct5 store `other`. */
  def amoResult(funct5: UInt, old: UInt, x: UInt, other: UInt, cmp: Option[UInt] = None): UInt =
    MuxLookup(funct5, other)(Seq(
      0x01.U -> x, 0x00.U -> (old + x), 0x04.U -> (old ^ x), 0x0C.U -> (old & x), 0x08.U -> (old | x),
      0x10.U -> Mux(old.asSInt < x.asSInt, old, x), 0x14.U -> Mux(old.asSInt < x.asSInt, x, old),
      0x18.U -> Mux(old < x, old, x), 0x1C.U -> Mux(old < x, x, old)) ++
      cmp.map(c => 0x05.U -> Mux(old === c, x, old)))
}
