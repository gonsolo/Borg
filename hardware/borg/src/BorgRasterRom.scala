// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** The edge-function rasterizer programs, in ROM so they do not take IMEM space.
  * The MMIO one reads uniforms 0-11 (dx_k, neg_dy_k, then neg_vx_k, neg_vy_k), leaves
  * E0..E2 in r1..r3 and the `rast_e*` CSRs, and clobbers r1..r5 on every pixel.
  */
private[borg] object BorgRasterRom {
  import Instructions._
  import Opcodes._
  private def ld(rd: Int, k: Int): BigInt = CSRRS(rd, 0, Csr.Uniform0 + k)
  private def out(csr: Int, rs: Int): BigInt = CSRRW(0, rs, csr)

  /** The registers holding E0..E2, then the draw front end's Zn and Zf. */
  val planeRegs: Seq[Int] = Seq(1, 2, 3, 4, 10)

  /** Edge k: e = dx_k * (y + neg_vy_k) + neg_dy_k * (x + neg_vx_k). */
  val instructions: Seq[BigInt] = (0 until 3).flatMap { k =>
    val e = planeRegs(k)
    Seq(ld(e, 6 + 2 * k), FADD_S(4, 30, e), ld(e, 7 + 2 * k), FADD_S(5, 31, e),
        ld(e, 2 * k), FMUL_S(e, e, 5), ld(5, 2 * k + 1), FMADD_S(e, 5, 4, e))
  } ++ (0 until 3).map(k => out(Csr.RastE0 + k, planeRegs(k))) :+ BigInt(0)

  /** The draw front end's per-pixel program (docs/B1_geometry_front_end.md): [[RasterUnit]] writes
    * the registers from the pixel centre in r30/r31, the program hands the coverage and depths to
    * the dispatcher through the `rast_*` CSRs. The per-sample depths last: a single-sample draw
    * fetches a HALT there (see drawSampleDepthPc).
    */
  private val draw: (Seq[BigInt], Int) = {
    val p = Seq.newBuilder[BigInt]
    p += RAST(30, 31)
    for (k <- 0 until 3) p += out(Csr.RastE0 + k, planeRegs(k))
    p += out(Csr.RastZn, 4); p += out(Csr.RastZf, 10); p += out(Csr.RastFragZ, 29)
    val samplePc = p.result().length
    for (s <- 0 until 4) p += out(Csr.RastSampleZ0 + s, 11 + s)
    p += BigInt(0)                               // HALT
    (p.result(), samplePc)
  }
  val drawInstructions: Seq[BigInt] = draw._1

  /** The first per-sample depth instruction; one-sample draws fetch a HALT here instead. */
  val drawSampleDepthPc: Int = draw._2
}
