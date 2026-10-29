// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

object Decode {
  // @doc:instruction-format
  /** A 32-bit instruction's register indices and op flags. An opcode the build leaves out decodes
    * as false everywhere, so it is inert rather than some other op. */
  def apply(instr: UInt, cfg: BorgConfig): (RegIndices, FpuOpFlags) = {
    val regs = Wire(new RegIndices())
    val rv = RvInt(instr)
    regs.rs1 := Mux(rv.csrReg, rv.sysReg, Mux(rv.csrWin, rv.sysWin, Instructions.BF_RS1(instr)))
    regs.rs2 := Instructions.BF_RS2(instr)
    regs.rs3 := Mux(rv.amocas, Instructions.BF_RD(instr), Instructions.BF_RS3(instr))
    regs.rd  := Instructions.BF_RD(instr)

    val flags = Wire(new FpuOpFlags())
    flags.mul   := rv.fmul
    flags.fma   := rv.r4
    flags.negC  := rv.fsub || rv.fmsub || rv.fnmadd
    flags.negProd := rv.fnmadd || rv.fnmsub
    flags.frcp  := rv.frcp
    flags.iadd  := rv.add
    flags.ishl  := rv.sll
    flags.ishr  := rv.sra
    flags.imul  := rv.mul
    flags.isub  := rv.sub
    flags.iand  := rv.and
    flags.ior   := rv.or
    flags.ixor  := rv.xor
    flags.islt  := rv.slt
    flags.f2h   := rv.fcvtHS
    flags.h2f   := rv.fcvtSH
    flags.frsq  := rv.frsq
    flags.ddx   := rv.ddx
    flags.ddy   := rv.ddy
    flags.fine  := Instructions.BF_RS2(instr) === 1.U     // DDX/DDY: per row or column
    flags.load  := rv.load || rv.amo
    flags.store := rv.store
    flags.branch := rv.br || rv.jal
    flags.expush := rv.expush
    flags.exelse := rv.exelse
    flags.expop  := rv.expop
    flags.execOp := flags.expush || flags.exelse || flags.expop
    flags.barrier := (if (cfg.hasCompute) rv.barrier else false.B)
    flags.exany  := rv.exany
    flags.isrl   := rv.srl
    flags.isltu  := rv.sltu
    flags.ztest  := rv.ztest
    flags.sout   := rv.sout
    flags.fattr  := rv.fattr
    flags.tex    := rv.texd
    flags.texs   := rv.texs
    flags.smask  := rv.csrMask
    flags.tld    := rv.tld
    flags.texa   := rv.texad
    flags.funct3 := Mux(rv.csrWin, 1.U, Mux(rv.tld, Instructions.BF_RS2(instr)(0), 0.U))
    flags.rvNoWrite := rv.any && regs.rd === 0.U || rv.csrrw
    flags.csrw := rv.csrrw
    flags.rvx  := rv.rvx
    flags.rvxOp := rv.rvxOp
    flags.csr  := rv.csr
    flags.explog := rv.fexp2 || rv.flog2
    flags.unit := flags.load || flags.store || flags.sout || flags.fattr || flags.tex || flags.texa ||
      flags.texs || flags.explog || flags.ztest

    (regs, flags)
  }
  // @doc:end
}
