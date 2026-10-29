// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** UNORM8 colour components (255 = 1.0), the working format of the blend unit. */
class Rgba8 extends Bundle {
  val r = UInt(8.W)
  val g = UInt(8.W)
  val b = UInt(8.W)
  val a = UInt(8.W)
}

/** Per-attachment blend state with Vulkan's enum values, so the driver passes them through. */
class BlendConfig extends Bundle {
  val enable         = Bool()
  val srcColorFactor = UInt(5.W)
  val dstColorFactor = UInt(5.W)
  val colorOp        = UInt(3.W)
  val srcAlphaFactor = UInt(5.W)
  val dstAlphaFactor = UInt(5.W)
  val alphaOp        = UInt(3.W)
  val constant       = new Rgba8   // VkPipelineColorBlendStateCreateInfo::blendConstants
  // VkColorComponentFlagBits (bit 0 = R .. 3 = A); masked channels keep the destination.
  // Vulkan applies the mask whether or not blending is enabled.
  val colorWriteMask = UInt(4.W)
}

/** Fixed-function colour blending in UNORM8, combinational, inlined into the tile write.
  *
  * The spec only asks for at least the destination's precision, and every attachment
  * Borg blends is UNORM8, so 8x8 multipliers suffice instead of FP16 ones.
  */
object BorgBlend {

  // VkBlendFactor values. SRC1_* (15..18) need dualSrcBlend, which Borg does not report.
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

  // VkBlendOp values; the advanced-blend ops need an extension Borg does not expose.
  val OP_ADD              = 0
  val OP_SUBTRACT         = 1
  val OP_REVERSE_SUBTRACT = 2
  val OP_MIN              = 3
  val OP_MAX              = 4

  val ONE_U8 = 255

  /** `round(a * b / 255)` for UNORM8 operands, exact for all 65536 pairs:
    * `t = a*b + 128; (t + (t >> 8)) >> 8` avoids a divider.
    */
  def mul255(a: UInt, b: UInt): UInt = {
    require(a.getWidth == 8 && b.getWidth == 8)
    val t = a * b +& 128.U          // 16 bits + carry headroom
    ((t +& (t >> 8)) >> 8)(7, 0)
  }

  /** Saturating UNORM8 add / subtract: Vulkan clamps fixed-point results, it does not wrap. */
  def satAdd(a: UInt, b: UInt): UInt = {
    val s = a +& b
    Mux(s > ONE_U8.U, ONE_U8.U(8.W), s(7, 0))
  }
  def satSub(a: UInt, b: UInt): UInt = Mux(a > b, a - b, 0.U(8.W))

  private def comp(c: Rgba8, ch: Int): UInt = ch match {
    case 0 => c.r
    case 1 => c.g
    case 2 => c.b
    case 3 => c.a
  }

  /** Resolve one VkBlendFactor for channel `ch`. For A, the *_COLOR factors use their
    * alpha component; only SRC_ALPHA_SATURATE differs (1 instead of `min(As, 1-Ad)`).
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

  /** Combine the two factored terms. MIN and MAX use the unfactored values, per spec. */
  def applyOp(op: UInt, srcTerm: UInt, dstTerm: UInt, srcRaw: UInt, dstRaw: UInt): UInt =
    MuxLookup(op, satAdd(srcTerm, dstTerm))(Seq(
      OP_ADD.U              -> satAdd(srcTerm, dstTerm),
      OP_SUBTRACT.U         -> satSub(srcTerm, dstTerm),
      OP_REVERSE_SUBTRACT.U -> satSub(dstTerm, srcTerm),
      OP_MIN.U              -> Mux(srcRaw < dstRaw, srcRaw, dstRaw),
      OP_MAX.U              -> Mux(srcRaw > dstRaw, srcRaw, dstRaw)
    ))

  private def blendChannel(
      ch: Int, srcFac: UInt, dstFac: UInt, op: UInt,
      src: Rgba8, dst: Rgba8, const: Rgba8): UInt = {
    val s = comp(src, ch)
    val d = comp(dst, ch)
    val sf = factor(srcFac, ch, src, dst, const)
    val df = factor(dstFac, ch, src, dst, const)
    applyOp(op, mul255(s, sf), mul255(d, df), s, d)
  }

  /** Full blend equation: RGB uses the colour factors and op, A the alpha ones. */
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

/** Module wrapper around [[BorgBlend.blend]] for tests. */
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
