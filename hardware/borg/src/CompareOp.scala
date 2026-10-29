// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** `VkCompareOp` as a comparator, shared by the depth and stencil tests. The values are the
  * Vulkan enum verbatim, so the driver passes it through.
  */
object CompareOp {
  val NEVER            = 0
  val LESS             = 1
  val EQUAL            = 2
  val LESS_OR_EQUAL    = 3
  val GREATER          = 4
  val NOT_EQUAL        = 5
  val GREATER_OR_EQUAL = 6
  val ALWAYS           = 7

  /** `a <op> b`, with `a` the incoming value and `b` the stored one, as the spec writes both
    * tests; swapping them turns LESS into GREATER.
    */
  def apply(op: UInt, a: UInt, b: UInt): Bool = MuxLookup(op, false.B)(Seq(
    NEVER.U            -> false.B,
    LESS.U             -> (a < b),
    EQUAL.U            -> (a === b),
    LESS_OR_EQUAL.U    -> (a <= b),
    GREATER.U          -> (a > b),
    NOT_EQUAL.U        -> (a =/= b),
    GREATER_OR_EQUAL.U -> (a >= b),
    ALWAYS.U           -> true.B
  ))
}
