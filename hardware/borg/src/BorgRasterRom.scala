// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** The edge-function rasterizer programs, in ROM so they do not take IMEM space.
  * The MMIO one reads uniforms 0-11 (dx_k, neg_dy_k, then neg_vx_k, neg_vy_k), leaves
  * E0..E2 in r1..r3 and the `rast_e*` CSRs, and clobbers r1..r5 on every pixel.
  */
private[borg] object BorgRasterRom {
  import Instructions._
  import Opcodes.{CSRRS, CSRRW, FSUB_S}
  private def ld(rd: Int, k: Int): BigInt = CSRRS(rd, 0, Csr.Uniform0 + k)
  private def out(csr: Int, rs: Int): BigInt = CSRRW(0, rs, csr)

  /** The registers holding E0..E2, then the draw front end's Zn and Zf. */
  val planeRegs: Seq[Int] = Seq(1, 2, 3, 4, 10)

  /** Edge k: e = dx_k * (y + neg_vy_k) + neg_dy_k * (x + neg_vx_k). */
  val instructions: Seq[BigInt] = (0 until 3).flatMap { k =>
    val e = planeRegs(k)
    Seq(ld(e, 6 + 2 * k), ADD(rs1 = 30, rs2 = e, rd = 4), ld(e, 7 + 2 * k), ADD(rs1 = 31, rs2 = e, rd = 5),
        ld(e, 2 * k), MUL(rs1 = e, rs2 = 5, rd = e), ld(5, 2 * k + 1), FMA(rs1 = 5, rs2 = 4, rs3 = e, rd = e))
  } ++ (0 until 3).map(k => out(Csr.RastE0 + k, planeRegs(k))) :+ BigInt(0)

  /** The draw front end's per-pixel program (docs/B1_geometry_front_end.md). Uniforms are
    * BorgSetupRom's Record; r30/r31 are the pixel centre. Leaves, also in the `rast_*` CSRs:
    *   r1..r3  E0..E2, screen-linear edge values, unscaled
    *   r4, r10 Zn = z_ndc and Zf = 1 - Zn, the near and far half-spaces
    *   r5..r7  perspective-correct barycentrics Ek / sum(E)
    *   r8      FragCoord.w = sum(E) = 1/w
    *   r29     FragCoord.z = Zn*scale + offset, also the fragment's depth
    *   r11..r14 the depth at each MSAA sample, which the depth test uses
    *           unless the shader writes r29 -- the last instructions, which a
    *           single-sample draw skips (see drawSampleDepthPc)
    * and clobbers r9.
    */
  private val draw: (Seq[BigInt], Int) = {
    val px = 30; val py = 31; val t = 9; val t2 = 10
    val R = BorgSetupRom.Record
    val p = Seq.newBuilder[BigInt]
    for (k <- 0 until 4) {                       // E0, E1, E2, Zn
      p += ld(t2, R.plane(k) + 1); p += MUL(rs1 = py, rs2 = t2, rd = t)
      p += ld(t2, R.plane(k) + 2); p += ADD(rs1 = t, rs2 = t2, rd = t)
      p += ld(t2, R.plane(k)); p += FMA(rs1 = px, rs2 = t2, rs3 = t, rd = planeRegs(k))
    }
    p += ADD(rs1 = 1, rs2 = 2, rd = 8)           // 1/w = E0 + E1 + E2
    p += ADD(rs1 = 8, rs2 = 3, rd = 8)
    p += FRCP(rs1 = 8, rd = t)                   // 1 / sum(E), plus one Newton step
    p += FNEG(rs1 = 8, rd = t2)
    p += ld(5, R.One); p += FMA(rs1 = t2, rs2 = t, rs3 = 5, rd = t2)
    p += FMA(rs1 = t, rs2 = t2, rs3 = t, rd = t)
    for (k <- 0 until 3) p += MUL(rs1 = planeRegs(k), rs2 = t, rd = 5 + k)
    p += ld(t2, R.InvDet); p += MUL(rs1 = 8, rs2 = t2, rd = 8)   // FragCoord.w = sum(E) / |det|
    p += ld(t2, R.DepthScale); p += MUL(rs1 = 4, rs2 = t2, rd = 29)
    p += ld(t2, R.DepthOffset); p += ADD(rs1 = 29, rs2 = t2, rd = 29)
    p += ld(t, R.One); p += FSUB_S(t2, t, 4)       // Zf = 1 - Zn, r10's last write
    for (k <- 0 until 3) p += out(Csr.RastE0 + k, planeRegs(k))
    p += out(Csr.RastZn, 4); p += out(Csr.RastZf, t2); p += out(Csr.RastFragZ, 29)
    // Each sample's own depth, r11..r14: FragCoord.z + {d0, d1, -d1, -d0}.
    val samplePc = p.result().length
    p += ld(11, R.SampleDepth); p += ADD(rs1 = 29, rs2 = 11, rd = 11)
    p += ld(12, R.SampleDepth + 1); p += ADD(rs1 = 29, rs2 = 12, rd = 12)
    p += ld(13, R.SampleDepth + 1); p += FSUB_S(13, 29, 13)
    p += ld(14, R.SampleDepth); p += FSUB_S(14, 29, 14)
    for (s <- 0 until 4) p += out(Csr.RastSampleZ0 + s, 11 + s)
    p += BigInt(0)                               // HALT
    (p.result(), samplePc)
  }
  val drawInstructions: Seq[BigInt] = draw._1

  /** The first per-sample depth instruction; one-sample draws fetch a HALT here instead. */
  val drawSampleDepthPc: Int = draw._2
}
