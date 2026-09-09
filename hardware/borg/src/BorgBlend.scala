// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** UNORM8 colour components, the working format of the blend unit.
  *
  * 0 represents 0.0 and 255 represents 1.0, so this is the same normalized
  * [0,1] encoding [[ColorQuantize]] already produces for the tile buffer's
  * narrow storage and the flusher's DRAM writes.
  */
class Rgba8 extends Bundle {
  val r = UInt(8.W)
  val g = UInt(8.W)
  val b = UInt(8.W)
  val a = UInt(8.W)
}

/** Per-attachment blend state, laid out exactly like Vulkan's
  * `VkPipelineColorBlendAttachmentState` so the driver can pass the
  * enum values straight through with no translation table.
  */
class BlendConfig extends Bundle {
  val enable         = Bool()
  val srcColorFactor = UInt(5.W)
  val dstColorFactor = UInt(5.W)
  val colorOp        = UInt(3.W)
  val srcAlphaFactor = UInt(5.W)
  val dstAlphaFactor = UInt(5.W)
  val alphaOp        = UInt(3.W)
  val constant       = new Rgba8   // VkPipelineColorBlendStateCreateInfo::blendConstants
}

/** Fixed-function colour blending (Vulkan-conformance item 9).
  *
  * Every tile write used to be an unconditional overwrite; Vulkan requires
  * blending as *core* functionality -- only `independentBlend` and
  * `dualSrcBlend` are the optional extras, which is easy to mis-read as the
  * whole feature being skippable.
  *
  * == Why UNORM8 and not FP16 ==
  *
  * The obvious implementation blends in the FP16 the shader produces. That
  * would cost eight FP16 multipliers (two per channel) plus adders -- large
  * next to the entire rest of the fixed-function path, on a chip whose tile
  * buffer was itself narrowed to UNORM8 to save area (commit 373b3228, where
  * the MSAA tile buffer was 26% of the whole wafer.space core).
  *
  * UNORM8 is not a shortcut here, it is the matching precision: the framebuffer
  * format Borg actually writes to DRAM is UNORM8, and the spec asks only that
  * blending be performed with "a precision and dynamic range no lower than
  * that used to represent destination components". Eight bits in, 16-bit
  * intermediates, correctly-rounded division by 255, eight bits out meets that
  * for a UNORM8 attachment. A float attachment format would need the FP path --
  * Borg does not expose one.
  *
  * The multipliers are therefore 8x8 rather than FP16, and there are no
  * denormal/NaN/exponent cases to get wrong.
  *
  * == Structure ==
  *
  * Purely combinational, expressed as `object` functions rather than a Module
  * so the caller (the tile-write path in [[BorgShaderDispatcher]]) can inline
  * it into an existing pipeline stage instead of paying a handshake for it.
  * [[BorgBlendUnit]] wraps it as a Module purely so the tests can drive it
  * directly.
  */
object BorgBlend {

  // --- VkBlendFactor (VK_BLEND_FACTOR_*), verbatim enum values ---------------
  // SRC1_* (15..18) are deliberately absent: they exist only under the
  // optional `dualSrcBlend` feature, which Borg reports unsupported, so an
  // application cannot legally reach them.
  val ZERO                     = 0
  val ONE                      = 1
  val SRC_COLOR                = 2
  val ONE_MINUS_SRC_COLOR      = 3
  val DST_COLOR                = 4
  val ONE_MINUS_DST_COLOR      = 5
  val SRC_ALPHA                = 6
  val ONE_MINUS_SRC_ALPHA      = 7
  val DST_ALPHA                = 8
  val ONE_MINUS_DST_ALPHA      = 9
  val CONSTANT_COLOR           = 10
  val ONE_MINUS_CONSTANT_COLOR = 11
  val CONSTANT_ALPHA           = 12
  val ONE_MINUS_CONSTANT_ALPHA = 13
  val SRC_ALPHA_SATURATE       = 14

  // --- VkBlendOp (VK_BLEND_OP_*), verbatim enum values ----------------------
  // All five are mandatory; the extended VK_BLEND_OP_*_EXT set is gated behind
  // VK_EXT_blend_operation_advanced, which Borg does not expose.
  val OP_ADD              = 0
  val OP_SUBTRACT         = 1
  val OP_REVERSE_SUBTRACT = 2
  val OP_MIN              = 3
  val OP_MAX              = 4

  val ONE_U8 = 255

  /** `round(a * b / 255)` for UNORM8 operands, exact for every one of the
    * 65536 input pairs.
    *
    * The textbook `(a*b + 127)/255` needs a real divider. The standard
    * reciprocal-free identity used instead -- `t = a*b + 128; (t + (t >> 8))
    * >> 8` -- is a multiply and two adds. It is exact, not approximate: the
    * `t >> 8` term supplies the 1/255 = 1/256 * 1/(1 - 1/256) correction.
    * `BorgBlendTests` checks it against `round(a*b/255.0)` for all 256 values
    * of one operand against every boundary and near-boundary value of the
    * other, rather than spot-checking a few products.
    */
  def mul255(a: UInt, b: UInt): UInt = {
    require(a.getWidth == 8 && b.getWidth == 8)
    val t = a * b +& 128.U          // 16 bits + carry headroom
    ((t +& (t >> 8)) >> 8)(7, 0)
  }

  /** Saturating UNORM8 add / subtract -- Vulkan clamps blend results into
    * [0,1] for a fixed-point attachment, it does not wrap. */
  def satAdd(a: UInt, b: UInt): UInt = {
    val s = a +& b
    Mux(s > ONE_U8.U, ONE_U8.U(8.W), s(7, 0))
  }
  def satSub(a: UInt, b: UInt): UInt = Mux(a > b, a - b, 0.U(8.W))

  /** Select channel `ch` (0=R, 1=G, 2=B, 3=A) of a colour. */
  private def comp(c: Rgba8, ch: Int): UInt = ch match {
    case 0 => c.r
    case 1 => c.g
    case 2 => c.b
    case 3 => c.a
  }

  /** Resolve one VkBlendFactor for channel `ch`.
    *
    * Note the alpha channel (`ch == 3`) is not special-cased for the
    * *_COLOR factors: per spec the factor applied to A is the alpha component
    * of the colour-valued factor, which is exactly `comp(_, 3)`. The single
    * genuine asymmetry is SRC_ALPHA_SATURATE, whose alpha factor is 1 rather
    * than `min(As, 1-Ad)`.
    */
  def factor(sel: UInt, ch: Int, src: Rgba8, dst: Rgba8, const: Rgba8): UInt = {
    val inv = (x: UInt) => ONE_U8.U(8.W) - x
    val saturate =
      if (ch == 3) ONE_U8.U(8.W)
      else {
        val oneMinusDstA = inv(dst.a)
        Mux(src.a < oneMinusDstA, src.a, oneMinusDstA)
      }

    MuxLookup(sel, 0.U(8.W))(Seq(
      ZERO.U                     -> 0.U(8.W),
      ONE.U                      -> ONE_U8.U(8.W),
      SRC_COLOR.U                -> comp(src, ch),
      ONE_MINUS_SRC_COLOR.U      -> inv(comp(src, ch)),
      DST_COLOR.U                -> comp(dst, ch),
      ONE_MINUS_DST_COLOR.U      -> inv(comp(dst, ch)),
      SRC_ALPHA.U                -> src.a,
      ONE_MINUS_SRC_ALPHA.U      -> inv(src.a),
      DST_ALPHA.U                -> dst.a,
      ONE_MINUS_DST_ALPHA.U      -> inv(dst.a),
      CONSTANT_COLOR.U           -> comp(const, ch),
      ONE_MINUS_CONSTANT_COLOR.U -> inv(comp(const, ch)),
      CONSTANT_ALPHA.U           -> const.a,
      ONE_MINUS_CONSTANT_ALPHA.U -> inv(const.a),
      SRC_ALPHA_SATURATE.U       -> saturate
    ))
  }

  /** Combine the two factored terms.
    *
    * MIN and MAX take the *unfactored* source and destination values -- the
    * spec defines them as `min(Rs0, Rd)` / `max(Rs0, Rd)` with no factor
    * involvement at all. Applying the factors first (the intuitive reading)
    * is a real conformance bug, so both raw values are passed in explicitly.
    */
  def applyOp(op: UInt, srcTerm: UInt, dstTerm: UInt, srcRaw: UInt, dstRaw: UInt): UInt =
    MuxLookup(op, satAdd(srcTerm, dstTerm))(Seq(
      OP_ADD.U              -> satAdd(srcTerm, dstTerm),
      OP_SUBTRACT.U         -> satSub(srcTerm, dstTerm),
      OP_REVERSE_SUBTRACT.U -> satSub(dstTerm, srcTerm),
      OP_MIN.U              -> Mux(srcRaw < dstRaw, srcRaw, dstRaw),
      OP_MAX.U              -> Mux(srcRaw > dstRaw, srcRaw, dstRaw)
    ))

  /** One channel of the blend equation. */
  private def blendChannel(
      ch: Int, srcFac: UInt, dstFac: UInt, op: UInt,
      src: Rgba8, dst: Rgba8, const: Rgba8): UInt = {
    val s = comp(src, ch)
    val d = comp(dst, ch)
    val sf = factor(srcFac, ch, src, dst, const)
    val df = factor(dstFac, ch, src, dst, const)
    applyOp(op, mul255(s, sf), mul255(d, df), s, d)
  }

  /** Full blend equation: RGB uses the colour factors/op, A the alpha ones.
    *
    * With `cfg.enable` low this is the identity on `src`, which is what keeps
    * a build that never enables blending bit-identical to the pre-blend
    * hardware at the tile-write port.
    */
  def blend(cfg: BlendConfig, src: Rgba8, dst: Rgba8): Rgba8 = {
    val out = Wire(new Rgba8)
    val blended = Wire(new Rgba8)
    blended.r := blendChannel(0, cfg.srcColorFactor, cfg.dstColorFactor, cfg.colorOp, src, dst, cfg.constant)
    blended.g := blendChannel(1, cfg.srcColorFactor, cfg.dstColorFactor, cfg.colorOp, src, dst, cfg.constant)
    blended.b := blendChannel(2, cfg.srcColorFactor, cfg.dstColorFactor, cfg.colorOp, src, dst, cfg.constant)
    blended.a := blendChannel(3, cfg.srcAlphaFactor, cfg.dstAlphaFactor, cfg.alphaOp, src, dst, cfg.constant)
    out := Mux(cfg.enable, blended, src)
    out
  }
}

/** Module wrapper around [[BorgBlend.blend]].
  *
  * Exists so the blend equation can be driven and checked directly by a
  * simulator without standing up the whole rasterizer; the shipping path
  * calls the `object` functions inline.
  */
class BorgBlendUnitIO extends Bundle {
  val cfg = Input(new BlendConfig)
  val src = Input(new Rgba8)
  val dst = Input(new Rgba8)
  val out = Output(new Rgba8)
}

class BorgBlendUnit extends Module {
  val io = IO(new BorgBlendUnitIO)
  io.out := BorgBlend.blend(io.cfg, io.src, io.dst)
}
