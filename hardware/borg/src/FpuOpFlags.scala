// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._

class FpuOpFlags extends Bundle {
  val fma    = Bool()
  val mul    = Bool()
  val frcp   = Bool()
  // Integer ALU ops (16-bit, operate on the raw register bits).
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
  // Memory access (LS_BASE + (rs1 << 2)). Both stall the core like TEX/TEXA; the
  // lane's own ALU write-back never fires for them, because the shared
  // memory FSM freezes busy_counter at 4 and write-back happens at 1.
  val load   = Bool()
  val store  = Bool()
  // Control flow: BEQ..BGEU and JAL. BorgLane suppresses write-back on it.
  val branch = Bool()
  // Execution-mask ops. Like `branch`, `execOp` exists so BorgLane can
  // suppress write-back for all three without decoding each: none of them
  // has a destination register.
  val expush = Bool()
  val exelse = Bool()
  val expop  = Bool()
  val execOp = Bool()
  // Compute only (BorgConfig.hasCompute) -- see Opcodes.MATCH_BARRIER_V2.
  val barrier = Bool()
  // See Opcodes.MATCH_EXANY_V2.
  val exany   = Bool()
  // Early per-fragment tests -- see Opcodes.MATCH_ZTEST_V2.
  val ztest   = Bool()
  // Draw front end -- see Opcodes.MATCH_SOUT_V2/MATCH_FATTR_V2.
  val sout    = Bool()
  val fattr   = Bool()
  // The descriptor-based texture unit.
  val tex     = Bool()
  val texa    = Bool()
  val smask   = Bool()   // SMASK
  val tld     = Bool()   // TLD
  val fine    = Bool()   // DDX/DDY: fine derivatives (rs2 = 1)
  val isrl    = Bool()   // logical shift right (RISC-V SRL)
  val isltu   = Bool()   // unsigned compare (RISC-V SLTU)
  val funct3 = UInt(3.W)
  val rvNoWrite = Bool()  // a RISC-V instruction writing x0, or a CSR access (no reads yet)
  val csrw    = Bool()     // CSRRW
  val texs    = Bool()     // TEXS: sampler descriptor address and control word
  val rvx     = Bool()     // a Zmmul/Zicond/Zbb op (B8 step 3), rvxOp says which
  val rvxOp   = UInt(6.W)
  val negC    = Bool()     // FSUB, FMSUB, FNMADD: the FMA's addend negated (B8 step 4)
  val negProd = Bool()     // FNMADD, FNMSUB: the FMA's product negated
  val csr     = UInt(12.W)
}
