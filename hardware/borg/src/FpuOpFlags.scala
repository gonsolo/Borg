// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class FpuOpFlags extends Bundle {
  val fma    = Bool()
  val mul    = Bool()
  val frcp   = Bool()
  // Integer ALU ops on the raw register bits.
  val iadd   = Bool()
  val ishl   = Bool()
  val ishr   = Bool()
  val imul   = Bool()
  val isub   = Bool()
  val iand   = Bool()
  val ior    = Bool()
  val ixor   = Bool()
  val islt   = Bool()
  val f2h    = Bool()   // FP32 -> FP16 (round to nearest even)
  val h2f    = Bool()   // FP16 -> FP32 (exact)
  val frsq   = Bool()
  val ddx    = Bool() // quad derivative d/dx (cross-lane: lane1 - lane0)
  val ddy    = Bool() // quad derivative d/dy (cross-lane: lane2 - lane0)
  // LS_BASE + (rs1 << 2); both stall the core like TEX/TEXA.
  val load   = Bool()
  val store  = Bool()
  // BEQ..BGEU and JAL; no write-back.
  val branch = Bool()
  // Execution-mask ops; `execOp` marks all three as having no destination register.
  val expush = Bool()
  val exelse = Bool()
  val expop  = Bool()
  val execOp = Bool()
  val barrier = Bool()   // compute only
  val exany   = Bool()
  val ztest   = Bool()   // early per-fragment tests
  val sout    = Bool()
  val fattr   = Bool()
  val tex     = Bool()
  val texa    = Bool()
  val smask   = Bool()
  val tld     = Bool()
  val fine    = Bool()   // DDX/DDY: fine derivatives (rs2 = 1)
  val isrl    = Bool()   // logical shift right (RISC-V SRL)
  val isltu   = Bool()   // unsigned compare (RISC-V SLTU)
  val funct3 = UInt(3.W)
  val rvNoWrite = Bool()  // a RISC-V instruction writing x0, or a CSR access (no reads yet)
  val csrw    = Bool()     // CSRRW
  val texs    = Bool()     // TEXS: sampler descriptor address and control word
  val rvx     = Bool()     // a Zmmul/Zicond/Zbb op, rvxOp says which
  val rvxOp   = UInt(6.W)
  val negC    = Bool()     // FSUB, FMSUB, FNMADD: the FMA's addend negated
  val negProd = Bool()     // FNMADD, FNMSUB: the FMA's product negated
  val csr     = UInt(12.W)
}
