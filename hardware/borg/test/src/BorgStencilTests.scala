// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Tests for the stencil test/update unit (Vulkan-conformance item 10).
  *
  * Like [[BorgBlendTests]] this drives an independent Scala reference model
  * of the spec rather than restating the RTL, and sweeps the full cross
  * product instead of spot-checking. The specific traps checked deliberately:
  *
  *   - the buffer is updated on *all three* outcomes, including the two that
  *     discard the fragment (an implementation that only writes on a passing
  *     fragment looks fine in a depth-pass test and is useless for masking,
  *     outlining or shadow volumes, which is all stencil is for);
  *   - `compareMask` masks the reference too, not just the stored value;
  *   - `writeMask` gates the write, not the operation;
  *   - CLAMP and WRAP differ only at 0 and 255 -- exactly where the
  *     algorithms that use them live.
  */
object BorgStencilTests extends TestSuite {

  import BorgStencil._

  // --- Reference model ------------------------------------------------------

  case class Face(compareOp: Int, failOp: Int, passOp: Int, depthFailOp: Int,
                  compareMask: Int, writeMask: Int, reference: Int)

  val PLAIN = Face(CompareOp.ALWAYS, KEEP, KEEP, KEEP, 0xFF, 0xFF, 0)

  def refCompare(op: Int, a: Int, b: Int): Boolean = op match {
    case CompareOp.NEVER            => false
    case CompareOp.LESS             => a < b
    case CompareOp.EQUAL            => a == b
    case CompareOp.LESS_OR_EQUAL    => a <= b
    case CompareOp.GREATER          => a > b
    case CompareOp.NOT_EQUAL        => a != b
    case CompareOp.GREATER_OR_EQUAL => a >= b
    case CompareOp.ALWAYS           => true
  }

  def refOp(op: Int, stored: Int, reference: Int): Int = op match {
    case KEEP                => stored
    case ZERO                => 0
    case REPLACE             => reference
    case INCREMENT_AND_CLAMP => math.min(255, stored + 1)
    case DECREMENT_AND_CLAMP => math.max(0, stored - 1)
    case INVERT              => (~stored) & 0xFF
    case INCREMENT_AND_WRAP  => (stored + 1) & 0xFF
    case DECREMENT_AND_WRAP  => (stored - 1) & 0xFF
  }

  /** (pass, newStencil) per the spec's three-way branch. */
  def refEvaluate(enable: Boolean, f: Face, stored: Int, depthPass: Boolean): (Boolean, Int) = {
    if (!enable) return (depthPass, stored)
    val sPass = refCompare(f.compareOp, f.reference & f.compareMask, stored & f.compareMask)
    val op = if (!sPass) f.failOp else if (depthPass) f.passOp else f.depthFailOp
    val updated = refOp(op, stored, f.reference)
    val written = (updated & f.writeMask) | (stored & ~f.writeMask & 0xFF)
    (sPass && depthPass, written)
  }

  // --- Drive helpers --------------------------------------------------------

  def pokeFace(port: StencilFaceConfig, f: Face): Unit = {
    port.compareOp.poke(f.compareOp.U)
    port.failOp.poke(f.failOp.U)
    port.passOp.poke(f.passOp.U)
    port.depthFailOp.poke(f.depthFailOp.U)
    port.compareMask.poke(f.compareMask.U)
    port.writeMask.poke(f.writeMask.U)
    port.reference.poke(f.reference.U)
  }

  def run(d: BorgStencilUnit, enable: Boolean, front: Face, stored: Int,
          depthPass: Boolean, frontFacing: Boolean = true,
          back: Face = PLAIN): (Boolean, Int) = {
    d.io.cfg.enable.poke(enable.B)
    pokeFace(d.io.cfg.front, front)
    pokeFace(d.io.cfg.back, back)
    d.io.frontFacing.poke(frontFacing.B)
    d.io.stored.poke(stored.U)
    d.io.depthPass.poke(depthPass.B)
    d.clock.step(1)
    (d.io.pass.peek().litToBoolean, d.io.newValue.peek().litValue.toInt)
  }

  val ALL_STENCIL_OPS = Seq(
    ("KEEP", KEEP), ("ZERO", ZERO), ("REPLACE", REPLACE),
    ("INCREMENT_AND_CLAMP", INCREMENT_AND_CLAMP), ("DECREMENT_AND_CLAMP", DECREMENT_AND_CLAMP),
    ("INVERT", INVERT), ("INCREMENT_AND_WRAP", INCREMENT_AND_WRAP),
    ("DECREMENT_AND_WRAP", DECREMENT_AND_WRAP))

  val ALL_COMPARE_OPS = Seq(
    ("NEVER", CompareOp.NEVER), ("LESS", CompareOp.LESS), ("EQUAL", CompareOp.EQUAL),
    ("LESS_OR_EQUAL", CompareOp.LESS_OR_EQUAL), ("GREATER", CompareOp.GREATER),
    ("NOT_EQUAL", CompareOp.NOT_EQUAL), ("GREATER_OR_EQUAL", CompareOp.GREATER_OR_EQUAL),
    ("ALWAYS", CompareOp.ALWAYS))

  val tests = Tests {

    utest.test("disabled_stencil_is_the_pre_stencil_behaviour") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: enable=0 ---")
        // Configured with state that would visibly change things if the gate
        // leaked: a NEVER test and a ZERO fail op.
        val f = Face(CompareOp.NEVER, ZERO, ZERO, ZERO, 0xFF, 0xFF, 0)
        for (depthPass <- Seq(true, false)) {
          val (pass, nv) = run(d, enable = false, f, stored = 0x42, depthPass = depthPass)
          println(f"  depthPass=$depthPass -> pass=$pass stencil=0x${nv.toHexString}")
          utest.assert(pass == depthPass)
          utest.assert(nv == 0x42)
        }
        println("  PASSED")
      }
    }

    utest.test("all_8_compare_ops_against_the_stored_value") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: all 8 VkCompareOp values ---")
        // reference = 0x40 against stored 0x30 / 0x40 / 0x50, i.e. ref >, ==,
        // < stored. The spec's operand order is (ref op stored), so GREATER
        // must pass for stored=0x30 -- reversing the operands here is the
        // single most likely silent bug in the whole unit.
        for ((name, op) <- ALL_COMPARE_OPS) {
          val f = PLAIN.copy(compareOp = op, reference = 0x40)
          val got = Seq(0x30, 0x40, 0x50).map { s =>
            run(d, enable = true, f, stored = s, depthPass = true)._1
          }
          val exp = Seq(0x30, 0x40, 0x50).map { s =>
            refEvaluate(true, f, s, depthPass = true)._1
          }
          println(f"  $name%-17s ref>stored=${got(0)}%-5s ref==stored=${got(1)}%-5s ref<stored=${got(2)}%-5s")
          utest.assert(got == exp)
        }
        println("  PASSED")
      }
    }

    utest.test("all_8_stencil_ops_including_the_clamp_wrap_boundaries") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: all 8 VkStencilOp values at 0, mid, 255 ---")
        for ((name, op) <- ALL_STENCIL_OPS) {
          // ALWAYS + depthPass routes straight to passOp, isolating the op.
          val f = PLAIN.copy(passOp = op, reference = 0x7F)
          val got = Seq(0, 0x40, 255).map { s =>
            run(d, enable = true, f, stored = s, depthPass = true)._2
          }
          val exp = Seq(0, 0x40, 255).map { s => refEvaluate(true, f, s, true)._2 }
          println(f"  $name%-20s 0->${got(0)}%-4d 64->${got(1)}%-4d 255->${got(2)}%-4d")
          utest.assert(got == exp)
        }
        // The distinction that matters: at the boundary CLAMP holds, WRAP rolls.
        val clampUp = run(d, true, PLAIN.copy(passOp = INCREMENT_AND_CLAMP), 255, true)._2
        val wrapUp  = run(d, true, PLAIN.copy(passOp = INCREMENT_AND_WRAP), 255, true)._2
        val clampDn = run(d, true, PLAIN.copy(passOp = DECREMENT_AND_CLAMP), 0, true)._2
        val wrapDn  = run(d, true, PLAIN.copy(passOp = DECREMENT_AND_WRAP), 0, true)._2
        println(f"  INC at 255: clamp=$clampUp wrap=$wrapUp; DEC at 0: clamp=$clampDn wrap=$wrapDn")
        utest.assert(clampUp == 255 && wrapUp == 0)
        utest.assert(clampDn == 0 && wrapDn == 255)
        println("  PASSED")
      }
    }

    utest.test("the_buffer_is_written_on_all_three_outcomes") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: three-way branch selects the right op ---")
        // Three distinguishable ops so the arm taken is unambiguous from the
        // stored value alone: fail -> ZERO, depthFail -> REPLACE(0x55),
        // pass -> INVERT.
        val f = Face(CompareOp.EQUAL, ZERO, INVERT, REPLACE, 0xFF, 0xFF, 0x55)

        // Arm 1: stencil fails (stored != reference) -> failOp, fragment dies,
        // but the buffer is still zeroed. This is the arm a naive
        // "only write when the fragment survives" implementation gets wrong.
        val (p1, v1) = run(d, true, f, stored = 0x11, depthPass = true)
        println(f"  stencil fail  -> pass=$p1 stencil=0x${v1.toHexString} (expect false, 0x0)")
        utest.assert(!p1 && v1 == 0x00)

        // Arm 2: stencil passes, depth fails -> depthFailOp, fragment dies,
        // buffer takes the reference.
        val (p2, v2) = run(d, true, f, stored = 0x55, depthPass = false)
        println(f"  depth fail    -> pass=$p2 stencil=0x${v2.toHexString} (expect false, 0x55)")
        utest.assert(!p2 && v2 == 0x55)

        // Arm 3: both pass -> passOp, fragment written.
        val (p3, v3) = run(d, true, f, stored = 0x55, depthPass = true)
        println(f"  both pass     -> pass=$p3 stencil=0x${v3.toHexString} (expect true, 0xaa)")
        utest.assert(p3 && v3 == 0xAA)
        println("  PASSED")
      }
    }

    utest.test("compare_mask_masks_the_reference_too") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: compareMask applies to both operands ---")
        // reference 0xF5, stored 0x05, mask 0x0F. Masking only the stored
        // value gives 0xF5 == 0x05 -> false; masking both (correct) gives
        // 0x05 == 0x05 -> true.
        val f = PLAIN.copy(compareOp = CompareOp.EQUAL, compareMask = 0x0F, reference = 0xF5)
        val (pass, _) = run(d, true, f, stored = 0x05, depthPass = true)
        println(f"  ref=0xF5 stored=0x05 mask=0x0F -> pass=$pass (expect true)")
        utest.assert(pass)
        println("  PASSED")
      }
    }

    utest.test("write_mask_gates_the_write_not_the_operation") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: writeMask is a bit-level merge ---")
        // INVERT of 0x0F is 0xF0; with writeMask 0x3C only those bits move:
        // (0xF0 & 0x3C) | (0x0F & 0xC3) = 0x30 | 0x03 = 0x33.
        val f = PLAIN.copy(passOp = INVERT, writeMask = 0x3C)
        val (_, nv) = run(d, true, f, stored = 0x0F, depthPass = true)
        println(f"  INVERT(0x0F)=0xF0 under writeMask 0x3C -> 0x${nv.toHexString} (expect 0x33)")
        utest.assert(nv == 0x33)

        // writeMask 0 must leave the buffer completely untouched while the
        // test itself still decides the fragment's fate.
        val fNone = PLAIN.copy(compareOp = CompareOp.NEVER, failOp = ZERO, writeMask = 0x00)
        val (pass, keep) = run(d, true, fNone, stored = 0x9C, depthPass = true)
        println(f"  writeMask=0 -> pass=$pass stencil=0x${keep.toHexString} (expect false, 0x9c)")
        utest.assert(!pass && keep == 0x9C)
        println("  PASSED")
      }
    }

    utest.test("front_and_back_faces_use_separate_state") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: face selection ---")
        // Vulkan always has independent front/back state -- no feature bit
        // makes them share -- so this is a datapath property, not something
        // firmware is expected to swap between draws.
        val front = PLAIN.copy(compareOp = CompareOp.ALWAYS, passOp = REPLACE, reference = 0xAA)
        val back  = PLAIN.copy(compareOp = CompareOp.NEVER,  failOp = ZERO,    reference = 0xBB)

        val (fp, fv) = run(d, true, front, stored = 0x11, depthPass = true,
                           frontFacing = true, back = back)
        println(f"  front -> pass=$fp stencil=0x${fv.toHexString} (expect true, 0xaa)")
        utest.assert(fp && fv == 0xAA)

        val (bp, bv) = run(d, true, front, stored = 0x11, depthPass = true,
                           frontFacing = false, back = back)
        println(f"  back  -> pass=$bp stencil=0x${bv.toHexString} (expect false, 0x0)")
        utest.assert(!bp && bv == 0x00)
        println("  PASSED")
      }
    }

    utest.test("compare_x_op_x_depth_cross_product_matches_reference") {
      simulate(new BorgStencilUnit) { d =>
        println("\n--- BorgStencil: cross product against the reference model ---")
        var n = 0
        for ((_, cmp) <- ALL_COMPARE_OPS;
             (_, op)  <- ALL_STENCIL_OPS;
             depthPass <- Seq(true, false);
             stored <- Seq(0, 1, 0x40, 0x55, 254, 255)) {
          // The same op in all three arms, so whichever arm the branch picks
          // the value is still checkable -- and `pass` independently pins
          // down which arm it must have been.
          val f = Face(cmp, op, op, op, 0xF3, 0x7E, 0x55)
          val got = run(d, enable = true, f, stored = stored, depthPass = depthPass)
          val exp = refEvaluate(true, f, stored, depthPass)
          if (got != exp)
            sys.error(s"cmp=$cmp op=$op depth=$depthPass stored=$stored: got $got expected $exp")
          n += 1
        }
        println(f"  $n configurations match the reference model")
        println("  PASSED")
      }
    }
  }
}
