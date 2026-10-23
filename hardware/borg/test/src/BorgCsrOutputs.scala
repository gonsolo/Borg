// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Test programs written for the v1 output registers (r19, r24-r29), with the CSR writes the
  * hardware takes since B8 step 8 added before the final HALT: the last value is the output. */
object CsrOutputs {
  import Opcodes._
  private val outs = Seq(26 -> Csr.OutC0, 27 -> Csr.OutC1, 28 -> Csr.OutC2, 24 -> Csr.OutC3,
                         29 -> Csr.OutDepth, 25 -> Csr.Discard, 19 -> Csr.SampleMask)
  private val noDst = Set(FUNCT7_STORE, FUNCT7_SOUT, FUNCT7_BRZ, FUNCT7_BRNZ, FUNCT7_JMP, FUNCT7_EXPUSH,
                          FUNCT7_EXELSE, FUNCT7_EXPOP, FUNCT7_BARRIER, FUNCT7_ZTEST)

  /** The registers an instruction word writes. */
  def writes(w: BigInt): Seq[Int] = {
    val (op, rd, f7, f2) = ((w & 0x7f).toInt, ((w >> 7) & 31).toInt, ((w >> 25) & 0x7f).toInt, ((w >> 25) & 3).toInt)
    if (w == 0) Nil
    else if (op == 4) { if (f2 == FUNCT2_TEX) rd until rd + 4 else if (f2 == FUNCT2_TEXA) Nil else Seq(rd) }
    else if (op == 0) { if (noDst(f7)) Nil else if (f7 == FUNCT7_FATTR) rd until rd + 3 else Seq(rd) }
    else if (op == OPCODE_CSRRW) Nil
    else Seq(rd)
  }

  def apply(prog: Seq[BigInt]): Seq[BigInt] = {
    val body = if (prog.lastOption.contains(BigInt(0))) prog.init else prog
    val written = body.flatMap(writes).toSet
    body ++ outs.collect { case (r, c) if written(r) => CSRRW(rs1 = r, csr = c) } :+ BigInt(0)
  }
}
