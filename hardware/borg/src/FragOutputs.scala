// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** A quad's fragment outputs, for [[BorgShaderDispatcher]]: out_c0..3 (R, G, B, A), out_depth,
  * discard and sample_mask from the fragment shader's CSR writes (docs/B8_isa_v2.md), and in draw
  * mode FragCoord.z and the per-sample depths from the raster ROM's. Each quad slot keeps its
  * own; [[at]] gives one slot's. */
class FragOutputs(cfg: BorgConfig, drawMode: Bool, depthUnorm: Bool, sampleCfg: SampleMaskConfig) {
  private val N = cfg.fragLanes
  private val all = ((1 << cfg.samples) - 1).U(cfg.samples.W)
  private def perSlot[T <: Data](x: => T): Vec[T] = VecInit(Seq.fill(cfg.quadSlots)(x))
  // R, G, B and A are FP16, what the tile buffer stores; depth is the attachment's precision.
  val frag_r = RegInit(perSlot(VecInit(Seq.fill(N)(0.U(16.W)))))
  val frag_g = RegInit(perSlot(VecInit(Seq.fill(N)(0.U(16.W)))))
  val frag_b = RegInit(perSlot(VecInit(Seq.fill(N)(0.U(16.W)))))
  val frag_z = RegInit(perSlot(VecInit(Seq.fill(N)(0.U(cfg.tileDepthBits.W)))))
  val frag_a = Option.when(cfg.hasBlend)(RegInit(perSlot(VecInit(Seq.fill(N)(0x3C00.U(16.W))))))   // opaque
  // RAW formats: out_c0 whole, the bytes the attachment stores, and out_c1, RAW64's second word.
  // They ride the tile's UNORM8 channels and alpha plane, so they need hasBlend.
  val rawSupported = cfg.hasBlend
  val frag_raw  = Option.when(rawSupported)(Reg(Vec(cfg.quadSlots, Vec(N, UInt(32.W)))))
  val frag_raw1 = Option.when(rawSupported)(Reg(Vec(cfg.quadSlots, Vec(N, UInt(32.W)))))
  // Sticky over the invocation: a discarded fragment stays discarded.
  val killed = RegInit(perSlot(VecInit(Seq.fill(N)(false.B))))
  // Draw mode at MSAA: each sample's depth, unless the shader writes out_depth.
  val frag_zs  = Option.when(cfg.samples == 4)(Reg(Vec(cfg.quadSlots, Vec(N, Vec(4, UInt(32.W))))))
  val zWritten = RegInit(perSlot(VecInit(Seq.fill(N)(false.B))))
  val fragMask = RegInit(perSlot(VecInit(Seq.fill(N)(all))))   // gl_SampleMask, all ones until written

  /** Into a D16 attachment: clamped to [0, 1] and rounded to the format before the test, as
    * Vulkan converts first; the round trip is the flush's quantizer and the load's dequantizer, so
    * a reloaded value equals a freshly rendered one. */
  def zClamp(z: UInt): UInt = {
    val w = cfg.tileDepthBits
    val one = (if (w == 32) 0x3F800000L else 0x3C00L).U(w.W)
    val clamped = Mux(z(w - 1), 0.U(w.W), Mux(z > one, one, z))
    val rounded = if (w == 32) DepthQuantize.dequantize16Fp32(DepthQuantize.quantize16Fp32(clamped))
                  else DepthQuantize.dequantize16(DepthQuantize.quantize16(clamped))
    Mux(drawMode && depthUnorm, rounded, z)
  }

  /** Alpha to coverage: the first round(alpha * samples) samples, monotonic as Vulkan asks. */
  def alphaMask(a: UInt): UInt = {
    val o = (x: UInt) => Mux(x(15), ~x, x | 0x8000.U)            // FP16 order key
    val steps = (1 to cfg.samples).map { k =>
      val th = java.lang.Float.floatToFloat16(((k - 0.5) / cfg.samples).toFloat).toInt & 0xFFFF
      o(a) >= o(th.U(16.W))
    }
    ((1.U << PopCount(steps)) - 1.U)(cfg.samples - 1, 0)
  }

  /** Slot q's outputs. */
  class View(q: UInt) {
    val frag_r = FragOutputs.this.frag_r(q); val frag_g = FragOutputs.this.frag_g(q)
    val frag_b = FragOutputs.this.frag_b(q); val frag_z = FragOutputs.this.frag_z(q)
    val frag_a = FragOutputs.this.frag_a.map(_(q))
    val frag_raw = FragOutputs.this.frag_raw.map(_(q)); val frag_raw1 = FragOutputs.this.frag_raw1.map(_(q))
    val killed = FragOutputs.this.killed(q)
    def rawSupported = FragOutputs.this.rawSupported
    def zClamp(z: UInt): UInt = FragOutputs.this.zClamp(z)

    /** Lane l's depth at sample s, as the attachment takes it. */
    def zAt(l: UInt, s: UInt): UInt =
      zClamp(frag_zs.map(zs => Mux(drawMode && !zWritten(q)(l) && !sampleCfg.single, zs(q)(l)(s(1, 0)), frag_z(l))).getOrElse(frag_z(l)))

    /** The samples alpha to coverage and the shader's mask leave lane i after shading. */
    def postMask(i: UInt): Vec[Bool] = {
      val a2c = frag_a.map(a => alphaMask(a(i))).getOrElse(all)
      val m = Mux(sampleCfg.alphaToCov, a2c, all) & Mux(sampleCfg.shaderMask, fragMask(q)(i), all)
      // One sample: alpha to coverage covers it from 0.5, the shader's mask is its bit 0.
      val a1 = frag_a.map(a => !a(i)(15) && a(i) >= 0x3800.U).getOrElse(true.B)
      val m1 = (!sampleCfg.alphaToCov || a1) && (!sampleCfg.shaderMask || fragMask(q)(i)(0))
      VecInit(Mux(sampleCfg.single, Fill(cfg.samples, m1), m).asBools)
    }
  }
  def at(q: UInt): View = new View(q)

  /** A new quad in slot q: no discard, opaque, the full mask, depth not written. */
  def clear(q: UInt): Unit = for (i <- 0 until N) {
    killed(q)(i) := false.B
    frag_a.foreach(_(q)(i) := 0x3C00.U)
    fragMask(q)(i) := all
    zWritten(q)(i) := false.B
  }

  /** The raster ROM's depths for slot q, while it runs. */
  def captureRast(csr: Vec[CsrWriteIO], rast: Bool, q: UInt): Unit = for (i <- 0 until N) {
    val c = csr(i)
    def at(n: Int) = c.en && rast && c.addr === n.U
    when(drawMode && at(Csr.RastFragZ)) { frag_z(q)(i) := c.data(31, 0) }
    frag_zs.foreach { zs => for (smp <- 0 until 4) when(at(Csr.RastSampleZ0 + smp)) { zs(q)(i)(smp) := c.data(31, 0) } }
  }

  /** The fragment shader's writes for slot q, while it runs. FP32 colours round to the nearest FP16. */
  def captureFrag(csr: Vec[CsrWriteIO], frag: Bool, q: UInt): Unit = for (i <- 0 until N) {
    val c = csr(i)
    def at(n: Int) = c.en && frag && c.addr === n.U
    def narrow(d: UInt): UInt = Fp16Fp32.narrow(d)
    frag_a.foreach { a => when(at(Csr.OutC3)) { a(q)(i) := narrow(c.data) } }
    when(sampleCfg.shaderMask && at(Csr.SampleMask)) { fragMask(q)(i) := c.data(cfg.samples - 1, 0) }
    when(at(Csr.Discard)) { killed(q)(i) := killed(q)(i) || (c.data =/= 0.U) }
    when(at(Csr.OutC0)) { frag_r(q)(i) := narrow(c.data); frag_raw.foreach(_(q)(i) := c.data.pad(32)(31, 0)) }
    when(at(Csr.OutC1)) { frag_g(q)(i) := narrow(c.data); frag_raw1.foreach(_(q)(i) := c.data.pad(32)(31, 0)) }
    when(at(Csr.OutC2)) { frag_b(q)(i) := narrow(c.data) }
    when(at(Csr.OutDepth)) {
      zWritten(q)(i) := true.B
      frag_z(q)(i) := (if (cfg.tileDepthBits == 32) c.data(31, 0) else narrow(c.data))
    }
  }
}
