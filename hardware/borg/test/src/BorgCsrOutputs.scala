// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Test programs written for the v1 output registers (r19, r24-r29), with the CSR writes the
  * hardware takes since B8 step 8 added before the final HALT: the last value is the output. */
object CsrOutputs {
  import Opcodes._
  private val outs = Seq(26 -> Csr.OutC0, 27 -> Csr.OutC1, 28 -> Csr.OutC2, 24 -> Csr.OutC3,
                         29 -> Csr.OutDepth, 25 -> Csr.Discard, 19 -> Csr.SampleMask)

  /** The registers an instruction word writes. */
  def writes(w: BigInt): Seq[Int] = {
    val (op, rd) = ((w & 0x7f).toInt, ((w >> 7) & 31).toInt)
    if (w == 0) Nil
    else if (op == OPCODE_CSRRW && ((w >> 12) & 7) == 1) Nil
    else Seq(rd)
  }

  def apply(prog: Seq[BigInt]): Seq[BigInt] = {
    val body = if (prog.lastOption.contains(BigInt(0))) prog.init else prog
    val written = body.flatMap(writes).toSet
    body ++ outs.collect { case (r, c) if written(r) => CSRRW(rs1 = r, csr = c) } :+ BigInt(0)
  }
}
