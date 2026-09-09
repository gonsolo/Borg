// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** `VkCompareOp` as a comparator, shared by the depth and stencil tests.
  *
  * Vulkan uses one enum for both, with identical semantics, so they share one
  * implementation here too -- two copies of an 8-way mux would be two places
  * for an operand order or an off-by-one boundary case to drift apart, and a
  * wrong comparison shows up as subtly missing geometry rather than a
  * failure.
  *
  * The values are the Vulkan enum verbatim, so the driver passes `VkCompareOp`
  * through with no translation table.
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

  /** `a <op> b`, with `a` the incoming value and `b` the stored one.
    *
    * Operand order is the part worth being careful about: the spec writes the
    * depth test as "new <op> stored" and the stencil test as "(reference &
    * mask) <op> (stored & mask)", both with the incoming value on the left.
    * Swapping them silently turns LESS into GREATER.
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
