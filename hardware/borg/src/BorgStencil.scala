// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Per-face stencil state, laid out like `VkStencilOpState` so the driver
  * passes the Vulkan enum values through untranslated.
  *
  * `compareMask`/`writeMask`/`reference` are the three pieces of dynamic
  * state Vulkan lets an application change without rebuilding the pipeline
  * (`VK_DYNAMIC_STATE_STENCIL_*`), which is why they live in the same
  * register block as the ops rather than being baked in anywhere.
  */
class StencilFaceConfig extends Bundle {
  val compareOp   = UInt(3.W)   // VkCompareOp
  val failOp      = UInt(3.W)   // VkStencilOp, stencil test failed
  val passOp      = UInt(3.W)   // VkStencilOp, stencil and depth both passed
  val depthFailOp = UInt(3.W)   // VkStencilOp, stencil passed but depth failed
  val compareMask = UInt(8.W)
  val writeMask   = UInt(8.W)
  val reference   = UInt(8.W)
}

/** Stencil state for both faces. Vulkan always has separate front/back state
  * -- there is no feature bit making them share, unlike `independentBlend`
  * for colour attachments -- so the face selection is part of the datapath
  * rather than something firmware is expected to swap between draws. */
class StencilConfig extends Bundle {
  val enable = Bool()
  val front  = new StencilFaceConfig
  val back   = new StencilFaceConfig
}

/** Fixed-function stencil test and update (Vulkan-conformance item 10).
  *
  * Stencil is mandatory: no `VkPhysicalDeviceFeatures` bit gates it, and
  * Borg had no stencil concept anywhere.
  *
  * == The part that is easy to get wrong ==
  *
  * Stencil is not one test with one outcome, it is a three-way branch whose
  * arms are chosen by *both* the stencil and the depth result:
  *
  *   stencil fails            -> failOp,      fragment killed
  *   stencil passes, depth fails -> depthFailOp, fragment killed
  *   both pass                -> passOp,      fragment written
  *
  * so the stencil buffer is updated even when the fragment is discarded --
  * that is the entire point of the feature (masking, outlining, shadow
  * volumes all depend on it). An implementation that only updates stencil on
  * a written fragment looks correct in a simple depth-pass test and is
  * useless for everything stencil actually exists for.
  *
  * Note also that stencil is tested *before* depth but written *after* the
  * depth result is known, so the two cannot be evaluated in separate pipeline
  * stages without carrying the intermediate decision along.
  *
  * Purely combinational object functions, matching [[BorgBlend]]'s shape, so
  * the caller can inline the whole thing into the existing tile-write stage.
  * [[BorgStencilUnit]] wraps it as a Module for the tests.
  */
object BorgStencil {

  // --- VkStencilOp (VK_STENCIL_OP_*), verbatim enum values -----------------
  val KEEP                = 0
  val ZERO                = 1
  val REPLACE             = 2
  val INCREMENT_AND_CLAMP = 3
  val DECREMENT_AND_CLAMP = 4
  val INVERT              = 5
  val INCREMENT_AND_WRAP  = 6
  val DECREMENT_AND_WRAP  = 7

  val MAX_U8 = 255

  /** The stencil test: `(reference & compareMask) <op> (stored & compareMask)`.
    *
    * Both operands are masked, not just the stored one -- masking only the
    * buffer is a common shortcut that gives the wrong answer whenever the
    * reference has bits outside the mask.
    */
  def test(face: StencilFaceConfig, stored: UInt): Bool =
    CompareOp(face.compareOp,
              face.reference & face.compareMask,
              stored & face.compareMask)

  /** Apply one VkStencilOp to the stored value.
    *
    * CLAMP and WRAP differ only at the boundary, which is exactly where a
    * shadow-volume or outline algorithm lives, so they are distinct arms
    * rather than one saturating adder.
    */
  def applyOp(op: UInt, stored: UInt, reference: UInt): UInt =
    MuxLookup(op, stored)(Seq(
      KEEP.U                -> stored,
      ZERO.U                -> 0.U(8.W),
      REPLACE.U             -> reference,
      INCREMENT_AND_CLAMP.U -> Mux(stored === MAX_U8.U, MAX_U8.U(8.W), stored + 1.U),
      DECREMENT_AND_CLAMP.U -> Mux(stored === 0.U, 0.U(8.W), stored - 1.U),
      INVERT.U              -> ~stored,
      INCREMENT_AND_WRAP.U  -> (stored + 1.U)(7, 0),
      DECREMENT_AND_WRAP.U  -> (stored - 1.U)(7, 0)
    ))

  /** Merge an op result into the buffer under `writeMask`.
    *
    * The masked-out bits keep their stored value; the spec is explicit that
    * writeMask gates the *write*, not the operation, so an INVERT with a
    * partial mask inverts only the selected bits rather than being skipped.
    */
  def maskedWrite(face: StencilFaceConfig, stored: UInt, updated: UInt): UInt =
    (updated & face.writeMask) | (stored & (~face.writeMask).asUInt)

  /** Result of evaluating stencil (and folding in the depth result). */
  class Result extends Bundle {
    val pass     = Bool()   // fragment survives: stencil AND depth both passed
    val newValue = UInt(8.W) // stencil value to store, already write-masked
  }

  /** The full three-way stencil decision.
    *
    * @param cfg        both faces' state plus the global enable
    * @param frontFacing selects which face's state applies
    * @param stored     the stencil value currently in the buffer
    * @param depthPass  the depth test's result for this same sample
    *
    * With `cfg.enable` low this is `pass = depthPass` and the stored value
    * unchanged, which is exactly the pre-stencil behaviour.
    */
  def evaluate(cfg: StencilConfig, frontFacing: Bool, stored: UInt, depthPass: Bool): Result = {
    val face = Wire(new StencilFaceConfig)
    face := Mux(frontFacing, cfg.front, cfg.back)

    val sPass = test(face, stored)

    // Which op runs is the three-way branch documented above. Note the
    // fragment is killed in two of the three arms but the buffer is written
    // in all three.
    val op = Mux(!sPass, face.failOp, Mux(depthPass, face.passOp, face.depthFailOp))
    val updated = applyOp(op, stored, face.reference)

    val r = Wire(new Result)
    r.pass     := Mux(cfg.enable, sPass && depthPass, depthPass)
    r.newValue := Mux(cfg.enable, maskedWrite(face, stored, updated), stored)
    r
  }
}

class BorgStencilUnitIO extends Bundle {
  val cfg         = Input(new StencilConfig)
  val frontFacing = Input(Bool())
  val stored      = Input(UInt(8.W))
  val depthPass   = Input(Bool())
  val pass        = Output(Bool())
  val newValue    = Output(UInt(8.W))
}

/** Module wrapper around [[BorgStencil.evaluate]], for the tests. */
class BorgStencilUnit extends Module {
  val io = IO(new BorgStencilUnitIO)
  val r = BorgStencil.evaluate(io.cfg, io.frontFacing, io.stored, io.depthPass)
  io.pass     := r.pass
  io.newValue := r.newValue
}
