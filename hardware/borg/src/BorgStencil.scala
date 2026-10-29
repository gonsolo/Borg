// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Per-face stencil state, laid out like `VkStencilOpState` so the driver
  * passes the Vulkan enum values through untranslated. */
class StencilFaceConfig extends Bundle {
  val compareOp   = UInt(3.W)   // VkCompareOp
  val failOp      = UInt(3.W)   // VkStencilOp, stencil test failed
  val passOp      = UInt(3.W)   // VkStencilOp, stencil and depth both passed
  val depthFailOp = UInt(3.W)   // VkStencilOp, stencil passed but depth failed
  val compareMask = UInt(8.W)
  val writeMask   = UInt(8.W)
  val reference   = UInt(8.W)
}

/** Stencil state for both faces; Vulkan always has separate front and back state. */
class StencilConfig extends Bundle {
  val enable = Bool()
  val front  = new StencilFaceConfig
  val back   = new StencilFaceConfig
}

/** Fixed-function stencil test and update, combinational like [[BorgBlend]].
  *
  * Stencil is a three-way branch on both the stencil and the depth result:
  * stencil fails -> failOp, stencil passes but depth fails -> depthFailOp,
  * both pass -> passOp. The buffer is written in all three, even when the
  * fragment is killed.
  */
object BorgStencil {

  // VkStencilOp values.
  val KEEP                = 0
  val ZERO                = 1
  val REPLACE             = 2
  val INCREMENT_AND_CLAMP = 3
  val DECREMENT_AND_CLAMP = 4
  val INVERT              = 5
  val INCREMENT_AND_WRAP  = 6
  val DECREMENT_AND_WRAP  = 7

  val MAX_U8 = 255

  /** `(reference & compareMask) <op> (stored & compareMask)`: both operands masked. */
  def test(face: StencilFaceConfig, stored: UInt): Bool =
    CompareOp(face.compareOp,
              face.reference & face.compareMask,
              stored & face.compareMask)

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

  /** writeMask gates the write, not the op: masked-out bits keep their value. */
  def maskedWrite(face: StencilFaceConfig, stored: UInt, updated: UInt): UInt =
    (updated & face.writeMask) | (stored & (~face.writeMask).asUInt)

  class Result extends Bundle {
    val pass     = Bool()   // stencil and depth both passed
    val newValue = UInt(8.W) // already write-masked
  }

  def evaluate(cfg: StencilConfig, frontFacing: Bool, stored: UInt, depthPass: Bool): Result = {
    val face = Wire(new StencilFaceConfig)
    face := Mux(frontFacing, cfg.front, cfg.back)

    val sPass = test(face, stored)

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
