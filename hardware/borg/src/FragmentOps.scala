// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The per-fragment operations of one lane's tile write: depth and stencil tests, blending, the
  * write masks, occlusion counting. With blending or stencil at MSAA (`perSample`) each sample is
  * written on its own cycle, so it sees that sample's stored value; otherwise one colour goes to
  * every covered sample. Under ZTEST (`early`) only depth and stencil change, colour is rewritten
  * as read, and earlyPass keeps each sample's result for the late pass. */
class FragmentOps(cfg: BorgConfig, io: BorgShaderDispatcherIO, outs: FragOutputs#View, cov: QuadCoverage#View,
                  scissorPass: Vec[Bool], tileIndex: Vec[UInt], frontFacing: Option[Bool]) {
  val perSample = cfg.samples > 1 && (cfg.hasBlend || cfg.hasStencil)

  /** VkCompareOp on depths in float order: negative below positive, -0 equal to +0. */
  def depthPasses(newZ: UInt, oldZ: UInt): Bool = {
    val w = newZ.getWidth
    def key(z: UInt): UInt = {
      val msb = (BigInt(1) << (w - 1)).U(w.W)
      val zn = Mux(z === msb, 0.U(w.W), z)
      Mux(zn(w - 1), ~zn, zn | msb)
    }
    CompareOp(io.depthCompareOp, key(newZ), key(oldZ))
  }

  /** Lane `lane`'s write of sample `smp` (0 when not perSample), from the tile read 3 cycles before. */
  def write(lane: UInt, smp: UInt, early: Bool, earlyDone: Bool, earlyPass: Vec[Vec[Bool]]): Unit = {
    val rd = io.tileRead.data(smp)
    val rawActive  = io.rawColor && outs.rawSupported.B
    val srgbActive = io.srgbColor && outs.rawSupported.B

    // Blend on UNORM8, back to FP16; blending off passes the FP16 bits. The write mask always holds.
    val (blendR, blendG, blendB) = if (cfg.hasBlend) {
      val cfgIn = io.blendCfg.get
      val src = Wire(new Rgba8)
      src.r := ColorQuantize.quantize8(outs.frag_r(lane))
      src.g := ColorQuantize.quantize8(outs.frag_g(lane))
      src.b := ColorQuantize.quantize8(outs.frag_b(lane))
      src.a := ColorQuantize.quantize8(outs.frag_a.get(lane))
      val dst = Wire(new Rgba8)
      dst.r := ColorQuantize.quantize8(rd.r)
      dst.g := ColorQuantize.quantize8(rd.g)
      dst.b := ColorQuantize.quantize8(rd.b)
      dst.a := io.alphaRead.get(smp)
      val out = BorgBlend.blend(cfgIn, src, dst)
      io.alphaWrite.get     := Mux(cfgIn.enable, out.a, src.a)
      io.alphaWriteMask.get := cfgIn.colorWriteMask(3)
      val blended = Seq(
        Mux(cfgIn.enable, ColorQuantize.dequantize8(out.r), outs.frag_r(lane)),
        Mux(cfgIn.enable, ColorQuantize.dequantize8(out.g), outs.frag_g(lane)),
        Mux(cfgIn.enable, ColorQuantize.dequantize8(out.b), outs.frag_b(lane)))
      val masked = blended.zip(Seq(rd.r, rd.g, rd.b)).zipWithIndex.map { case ((b, d), i) =>
        Mux(cfgIn.colorWriteMask(i), b, d)
      }
      (masked(0), masked(1), masked(2))
    } else (outs.frag_r(lane), outs.frag_g(lane), outs.frag_b(lane))

    // RAW stores out_c0's bytes, sRGB the shader's colour: both unblended, the shader did it.
    val (outR, outG, outB) = outs.frag_raw match {
      case Some(raw) =>
        val w = raw(lane)
        when(rawActive) {
          io.alphaWrite.get     := w(31, 24)
          io.alphaWriteMask.get := true.B
        }.elsewhen(srgbActive) {
          io.alphaWrite.get     := ColorQuantize.quantize8(outs.frag_a.get(lane))
          io.alphaWriteMask.get := true.B
        }
        (Mux(rawActive, ColorQuantize.dequantize8(w(7, 0)), Mux(srgbActive, outs.frag_r(lane), blendR)),
         Mux(rawActive, ColorQuantize.dequantize8(w(15, 8)), Mux(srgbActive, outs.frag_g(lane), blendG)),
         Mux(rawActive, ColorQuantize.dequantize8(w(23, 16)), Mux(srgbActive, outs.frag_b(lane), blendB)))
      case None => (blendR, blendG, blendB)
    }
    io.extWrite.foreach(_ := Mux(early, io.extRead.get(smp), outs.frag_raw1.get(lane)))
    io.tileWrite.data.r := Mux(early, rd.r, outR)
    io.tileWrite.data.g := Mux(early, rd.g, outG)
    io.tileWrite.data.b := Mux(early, rd.b, outB)
    io.alphaWriteMask.foreach(m => when(early) { m := false.B })
    // depthWriteEnable needs one known sample per write; the broadcast path at MSAA always writes.
    // After ZTEST the depth is final in the tile buffer: keep it.
    io.tileWrite.data.z := (if (cfg.samples == 1 || perSample)
                              Mux(io.depthWriteEn && !earlyDone, outs.zAt(lane, smp), rd.z)
                            else outs.zClamp(outs.frag_z(lane)))

    // Stencil tests before depth but writes after it on every outcome, so it has its own enable.
    val stencilRes = Option.when(cfg.hasStencil)(BorgStencil.evaluate(io.stencilCfg.get, frontFacing.get,
      io.stencilRead.get(smp), depthPasses(outs.zAt(lane, smp), rd.z)))

    // Covered, not discarded, inside the scissor, and in the late pass inside alpha to coverage and
    // the shader's mask (the early tests ran without them).
    def reached(s: Int): Bool =
      cov.coverage(lane)(s) && !outs.killed(lane) && scissorPass(lane) && (early || outs.postMask(lane)(s))
    def reachedDyn(s: UInt): Bool =
      cov.coverage(lane)(s) && !outs.killed(lane) && scissorPass(lane) && (early || outs.postMask(lane)(s))

    if (perSample) {
      val testOk = stencilRes.map(_.pass).getOrElse(depthPasses(outs.zAt(lane, smp), rd.z))
      val pass = reachedDyn(smp) && Mux(earlyDone, earlyPass(lane)(smp), testOk)
      val oneHot = UIntToOH(smp, cfg.samples)
      io.tileWrite.coverage := Mux(pass, oneHot, 0.U)
      io.tileWrite.en       := pass
      when(early) { earlyPass(lane)(smp) := pass }
      // Counted where the tests run, never twice; one sample's identical copies count once.
      val countThis = !io.sampleCfg.single || smp === 0.U
      io.occSamples := (pass && !earlyDone && countThis).asUInt
      stencilRes.foreach { r =>
        io.stencilWrite.get     := r.newValue
        io.stencilWriteMask.get := Mux(reachedDyn(smp) && !earlyDone, oneHot, 0.U)
      }
    } else {
      val samplePass = (0 until cfg.samples).map { s =>
        val testOk = stencilRes match {
          case Some(r) if s == 0 => r.pass                  // folds in the depth result
          case _                 => depthPasses(outs.zAt(lane, s.U), io.tileRead.data(s).z)
        }
        reached(s) && Mux(earlyDone, earlyPass(lane)(s), testOk)
      }
      io.tileWrite.coverage := Cat(samplePass.reverse)
      io.tileWrite.en       := samplePass.reduce(_ || _)
      when(early) { samplePass.zipWithIndex.foreach { case (p, s) => earlyPass(lane)(s) := p } }
      io.occSamples := Mux(earlyDone, 0.U, Mux(io.sampleCfg.single, samplePass(0).asUInt, PopCount(samplePass)))
      stencilRes.foreach { r =>
        io.stencilWrite.get     := r.newValue
        io.stencilWriteMask.get := Mux(reached(0) && !earlyDone, Fill(cfg.samples, 1.U(1.W)), 0.U)
      }
    }
    if (BorgDebug.trace) printf("[DISP] tileWrite lane=%d smp=%d idx=%d Z=0x%x zOld=0x%x cov=0x%x\n",
      lane, smp, tileIndex(lane), outs.frag_z(lane), rd.z, io.tileWrite.coverage)
  }
}
