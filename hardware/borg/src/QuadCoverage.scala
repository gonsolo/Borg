// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The quad's coverage, for [[BorgShaderDispatcher]]: the raster ROM's edge values (and in draw
  * mode the near and far planes Zn and Zf) from its rast_* CSR writes, tested at the pixel centre
  * or per MSAA sample, then the pipeline's static sample mask.
  *
  * A value is outside when negative and nonzero: -0 is inside. In draw mode a sample exactly on an
  * edge belongs to the triangle only if that edge is a top or left one. At MSAA, sample s's edge
  * value is e + d_s with per-triangle deltas (covDelta, two per plane: the standard positions are
  * symmetric, d_2 = -d_1 and d_3 = -d_0); the test e >= -d_s compares the bit patterns in float
  * order, exactly. Zf = 1 - Zn moves the other way at every sample: its deltas are Zn's negated. */
class QuadCoverage(cfg: BorgConfig, drawMode: Bool, topLeft: Vec[Bool],
                   covDelta: Option[Vec[Vec[UInt]]], sampleCfg: SampleMaskConfig) {
  private val N = cfg.fragLanes
  private val w = cfg.totalBits
  private val nPlanes = 5

  val e0_outside = RegInit(VecInit(Seq.fill(N)(false.B)))
  val e1_outside = RegInit(VecInit(Seq.fill(N)(false.B)))
  val e2_outside = RegInit(VecInit(Seq.fill(N)(false.B)))
  val z_outside  = RegInit(VecInit(Seq.fill(N)(VecInit(false.B, false.B))))
  // The raw values, for the per-sample tests. The ROM writes every plane of every lane before
  // they are read, so they need no reset.
  val e_val = Option.when(cfg.samples > 1)(Reg(Vec(N, Vec(nPlanes, UInt(w.W)))))

  def ordered(x: UInt): UInt = Mux(x(w - 1), ~x, x | (1.U << (w - 1)))
  def fnegBits(x: UInt): UInt = x ^ (1.U << (w - 1))
  def isOutside(data: UInt): Bool = data(w - 1).asBool && data(w - 2, 0) =/= 0.U

  /** Per lane, per sample: inside the triangle. */
  val geom: Vec[Vec[Bool]] = cfg.samples match {
    case 1 =>
      VecInit((0 until N).map(i =>
        VecInit(Seq(!e0_outside(i) && !e1_outside(i) && !e2_outside(i) &&
                    (!drawMode || (!z_outside(i)(0) && !z_outside(i)(1)))))))
    case _ =>
      val deltas = (0 until cfg.coveragePlanesStored).map(e => (covDelta.get(e)(0), covDelta.get(e)(1)))
      val planeDeltas = deltas :+ ((fnegBits(deltas(3)._1), fnegBits(deltas(3)._2)))
      val threshRaw = VecInit(planeDeltas.map { case (d0, d1) => VecInit(Seq(fnegBits(d0), fnegBits(d1), d1, d0)) })
      val thresh = VecInit(threshRaw.map(t => VecInit(t.map(ordered))))
      def zeroMag(x: UInt) = x(w - 2, 0) === 0.U
      VecInit((0 until N).map { i =>
        VecInit((0 until cfg.samples).map { s =>
          def test(e: Int) = ordered(e_val.get(i)(e)) >= thresh(e)(s)
          def edge(e: Int) = {
            val v = e_val.get(i)(e); val th = threshRaw(e)(s)
            val tie = v === th || (zeroMag(v) && zeroMag(th))
            Mux(drawMode, (ordered(v) > thresh(e)(s) && !tie) || (tie && topLeft(e)), test(e))
          }
          (0 until 3).map(edge).reduce(_ && _) && (!drawMode || (test(3) && test(4)))
        })
      })
  }

  /** Per lane, per sample: covered, after the static sample mask. */
  val coverage: Vec[Vec[Bool]] = VecInit((0 until N).map(i =>
    VecInit((0 until cfg.samples).map { s =>
      val k = Mux(sampleCfg.single, 0.U, s.U)
      geom(i)(k) && sampleCfg.mask(k)
    })))
  /** gl_SampleMaskIn: one bit per rasterization sample. */
  val laneMask: Vec[UInt] = VecInit(coverage.map(c => Mux(sampleCfg.single, c(0).asUInt, Cat(c.reverse))))
  val inside: Vec[Bool] = VecInit((0 until N).map(i => coverage(i).reduce(_ || _)))
  val anyInside: Bool = inside.reduce(_ || _)

  /** A new quad. */
  def clear(): Unit = for (i <- 0 until N) {
    e0_outside(i) := false.B; e1_outside(i) := false.B; e2_outside(i) := false.B
    z_outside(i).foreach(_ := false.B)
  }

  /** The raster ROM's writes, while it runs. */
  def capture(csr: Vec[CsrWriteIO], rast: Bool): Unit = for (i <- 0 until N) {
    val c = csr(i)
    def at(n: Int) = c.en && rast && c.addr === n.U
    def out(e: Int): Bool = isOutside(c.data) || (drawMode && c.data(w - 2, 0) === 0.U && !topLeft(e))
    when(at(Csr.RastE0))     { e0_outside(i) := out(0) }
    when(at(Csr.RastE0 + 1)) { e1_outside(i) := out(1) }
    when(at(Csr.RastE0 + 2)) { e2_outside(i) := out(2) }
    when(at(Csr.RastZn)) { z_outside(i)(0) := isOutside(c.data) }
    when(at(Csr.RastZf)) { z_outside(i)(1) := isOutside(c.data) }
    val planeCsr = Seq(Csr.RastE0, Csr.RastE0 + 1, Csr.RastE0 + 2, Csr.RastZn, Csr.RastZf)
    e_val.foreach { ev => for (e <- 0 until nPlanes) when(at(planeCsr(e))) { ev(i)(e) := c.data(w - 1, 0) } }
  }
}
