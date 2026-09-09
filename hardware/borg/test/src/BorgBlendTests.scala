// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Tests for the fixed-function blend unit (Vulkan-conformance item 9).
  *
  * The whole point of blending is that it is a *table* of behaviours -- 15
  * legal source factors x 15 destination factors x 5 operations, separately
  * for colour and alpha -- so spot-checking "src over dst looks right" would
  * miss almost all of it. These tests drive an independent Scala reference
  * model of the spec's equations and compare against the RTL, plus check the
  * two rules that are easy to implement wrongly and impossible to notice in a
  * demo: MIN/MAX ignore the factors entirely, and SRC_ALPHA_SATURATE's alpha
  * factor is 1 rather than `min(As, 1-Ad)`.
  */
object BorgBlendTests extends TestSuite {

  import BorgBlend._

  // --- Reference model (spec equations, written independently of the RTL) ---

  case class C(r: Int, g: Int, b: Int, a: Int) {
    def comp(ch: Int): Int = ch match {
      case 0 => r; case 1 => g; case 2 => b; case 3 => a
    }
  }

  def refMul255(x: Int, f: Int): Int = math.round(x * f / 255.0).toInt

  def refFactor(sel: Int, ch: Int, src: C, dst: C, const: C): Int = sel match {
    case ZERO                     => 0
    case ONE                      => 255
    case SRC_COLOR                => src.comp(ch)
    case ONE_MINUS_SRC_COLOR      => 255 - src.comp(ch)
    case DST_COLOR                => dst.comp(ch)
    case ONE_MINUS_DST_COLOR      => 255 - dst.comp(ch)
    case SRC_ALPHA                => src.a
    case ONE_MINUS_SRC_ALPHA      => 255 - src.a
    case DST_ALPHA                => dst.a
    case ONE_MINUS_DST_ALPHA      => 255 - dst.a
    case CONSTANT_COLOR           => const.comp(ch)
    case ONE_MINUS_CONSTANT_COLOR => 255 - const.comp(ch)
    case CONSTANT_ALPHA           => const.a
    case ONE_MINUS_CONSTANT_ALPHA => 255 - const.a
    // The one genuine channel asymmetry in the whole factor table.
    case SRC_ALPHA_SATURATE       => if (ch == 3) 255 else math.min(src.a, 255 - dst.a)
  }

  def refOp(op: Int, sTerm: Int, dTerm: Int, sRaw: Int, dRaw: Int): Int = op match {
    case OP_ADD              => math.min(255, sTerm + dTerm)
    case OP_SUBTRACT         => math.max(0, sTerm - dTerm)
    case OP_REVERSE_SUBTRACT => math.max(0, dTerm - sTerm)
    case OP_MIN              => math.min(sRaw, dRaw)   // factors deliberately unused
    case OP_MAX              => math.max(sRaw, dRaw)
  }

  def refChannel(ch: Int, sf: Int, df: Int, op: Int, src: C, dst: C, const: C): Int = {
    val s = src.comp(ch)
    val d = dst.comp(ch)
    refOp(op, refMul255(s, refFactor(sf, ch, src, dst, const)),
              refMul255(d, refFactor(df, ch, src, dst, const)), s, d)
  }

  def refBlend(cSf: Int, cDf: Int, cOp: Int, aSf: Int, aDf: Int, aOp: Int,
               src: C, dst: C, const: C): C =
    C(refChannel(0, cSf, cDf, cOp, src, dst, const),
      refChannel(1, cSf, cDf, cOp, src, dst, const),
      refChannel(2, cSf, cDf, cOp, src, dst, const),
      refChannel(3, aSf, aDf, aOp, src, dst, const))

  // --- Drive helpers --------------------------------------------------------

  def pokeC(port: Rgba8, c: C): Unit = {
    port.r.poke(c.r.U); port.g.poke(c.g.U); port.b.poke(c.b.U); port.a.poke(c.a.U)
  }

  def peekC(port: Rgba8): C = C(
    port.r.peek().litValue.toInt, port.g.peek().litValue.toInt,
    port.b.peek().litValue.toInt, port.a.peek().litValue.toInt)

  /** Configure, apply, and read back one blend. Combinational, so no clock
    * step is needed beyond letting the simulator settle. */
  def doBlend(d: BorgBlendUnit, enable: Boolean,
              cSf: Int, cDf: Int, cOp: Int, aSf: Int, aDf: Int, aOp: Int,
              src: C, dst: C, const: C = C(0, 0, 0, 0)): C = {
    d.io.cfg.enable.poke(enable.B)
    d.io.cfg.srcColorFactor.poke(cSf.U)
    d.io.cfg.dstColorFactor.poke(cDf.U)
    d.io.cfg.colorOp.poke(cOp.U)
    d.io.cfg.srcAlphaFactor.poke(aSf.U)
    d.io.cfg.dstAlphaFactor.poke(aDf.U)
    d.io.cfg.alphaOp.poke(aOp.U)
    pokeC(d.io.cfg.constant, const)
    pokeC(d.io.src, src)
    pokeC(d.io.dst, dst)
    d.clock.step(1)
    peekC(d.io.out)
  }

  val ALL_FACTORS = Seq(
    ("ZERO", ZERO), ("ONE", ONE),
    ("SRC_COLOR", SRC_COLOR), ("ONE_MINUS_SRC_COLOR", ONE_MINUS_SRC_COLOR),
    ("DST_COLOR", DST_COLOR), ("ONE_MINUS_DST_COLOR", ONE_MINUS_DST_COLOR),
    ("SRC_ALPHA", SRC_ALPHA), ("ONE_MINUS_SRC_ALPHA", ONE_MINUS_SRC_ALPHA),
    ("DST_ALPHA", DST_ALPHA), ("ONE_MINUS_DST_ALPHA", ONE_MINUS_DST_ALPHA),
    ("CONSTANT_COLOR", CONSTANT_COLOR), ("ONE_MINUS_CONSTANT_COLOR", ONE_MINUS_CONSTANT_COLOR),
    ("CONSTANT_ALPHA", CONSTANT_ALPHA), ("ONE_MINUS_CONSTANT_ALPHA", ONE_MINUS_CONSTANT_ALPHA),
    ("SRC_ALPHA_SATURATE", SRC_ALPHA_SATURATE))

  val ALL_OPS = Seq(
    ("ADD", OP_ADD), ("SUBTRACT", OP_SUBTRACT), ("REVERSE_SUBTRACT", OP_REVERSE_SUBTRACT),
    ("MIN", OP_MIN), ("MAX", OP_MAX))

  val tests = Tests {

    // =========================================================================
    // The multiply-by-a-normalized-factor primitive
    // =========================================================================

    utest.test("mul255_matches_exact_rounding_over_a_wide_sweep") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: mul255 exactness ---")
        // out.r = mul255(src.r, const.r): CONSTANT_COLOR scales the source,
        // ZERO drops the destination term, ADD leaves the product alone.
        var checked = 0
        // Every operand value paired against the boundary and near-boundary
        // cases plus a spread of interior ones -- 0/1/2 and 253/254/255 are
        // where an off-by-one in the rounding correction shows up, 127/128/129
        // where a truncating implementation first diverges.
        val bs = Seq(0, 1, 2, 3, 63, 64, 85, 127, 128, 129, 170, 191, 200, 252, 253, 254, 255)
        for (b <- bs; a <- 0 until 256) {
          val got = doBlend(d, enable = true,
            CONSTANT_COLOR, ZERO, OP_ADD, ONE, ZERO, OP_ADD,
            src = C(a, a, a, 255), dst = C(0, 0, 0, 0), const = C(b, b, b, b))
          val exp = refMul255(a, b)
          if (got.r != exp)
            sys.error(s"mul255($a,$b) = ${got.r}, expected $exp")
          checked += 1
        }
        println(f"  $checked operand pairs exact (all 256 values x ${bs.length} factors)")
        println("  PASSED")
      }
    }

    // =========================================================================
    // The factor table
    // =========================================================================

    utest.test("all_15_blend_factors_match_the_spec_table") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: all 15 VkBlendFactor values ---")
        // Deliberately all-distinct components so a factor that picks the
        // wrong channel or the wrong operand cannot accidentally agree.
        val src   = C(200, 100, 50, 180)
        val dst   = C(30, 60, 90, 120)
        val const = C(10, 20, 40, 220)

        for ((name, f) <- ALL_FACTORS) {
          // dst factor ZERO isolates the source term, so the result is
          // exactly mul255(srcChannel, factor) -- one unknown per check.
          val got = doBlend(d, enable = true, f, ZERO, OP_ADD, f, ZERO, OP_ADD, src, dst, const)
          val exp = refBlend(f, ZERO, OP_ADD, f, ZERO, OP_ADD, src, dst, const)
          println(f"  $name%-26s -> (${got.r}%3d,${got.g}%3d,${got.b}%3d,${got.a}%3d)")
          utest.assert(got == exp)
        }
        println("  PASSED")
      }
    }

    utest.test("src_alpha_saturate_alpha_factor_is_one_not_the_rgb_saturate") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: SRC_ALPHA_SATURATE channel asymmetry ---")
        // As = 100, Ad = 200 -> RGB factor min(100, 55) = 55, alpha factor 255.
        val src   = C(255, 255, 255, 100)
        val dst   = C(0, 0, 0, 200)
        val got = doBlend(d, enable = true,
          SRC_ALPHA_SATURATE, ZERO, OP_ADD, SRC_ALPHA_SATURATE, ZERO, OP_ADD, src, dst)
        val expRgb = refMul255(255, math.min(100, 255 - 200))
        val expA   = refMul255(100, 255)
        println(f"  rgb=${got.r} (expect $expRgb), a=${got.a} (expect $expA)")
        utest.assert(got.r == expRgb && got.g == expRgb && got.b == expRgb)
        utest.assert(got.a == expA)
        println("  PASSED")
      }
    }

    // =========================================================================
    // The operation table
    // =========================================================================

    utest.test("all_five_blend_ops_match_the_spec") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: all 5 VkBlendOp values ---")
        val src = C(200, 100, 50, 180)
        val dst = C(30, 200, 90, 120)
        for ((name, op) <- ALL_OPS) {
          val got = doBlend(d, enable = true, ONE, ONE, op, ONE, ONE, op, src, dst)
          val exp = refBlend(ONE, ONE, op, ONE, ONE, op, src, dst, C(0, 0, 0, 0))
          println(f"  $name%-17s -> (${got.r}%3d,${got.g}%3d,${got.b}%3d,${got.a}%3d)")
          utest.assert(got == exp)
        }
        println("  PASSED")
      }
    }

    utest.test("min_and_max_ignore_the_blend_factors") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: MIN/MAX use unfactored operands ---")
        // The trap: applying the factors before min/max is the intuitive
        // reading and is wrong. With both factors ZERO a factor-applying
        // implementation returns 0; the spec says min(Rs0, Rd) regardless.
        val src = C(200, 100, 50, 180)
        val dst = C(30, 200, 90, 120)

        val gotMin = doBlend(d, enable = true, ZERO, ZERO, OP_MIN, ZERO, ZERO, OP_MIN, src, dst)
        println(f"  MIN with both factors ZERO -> (${gotMin.r},${gotMin.g},${gotMin.b},${gotMin.a})")
        utest.assert(gotMin == C(30, 100, 50, 120))

        val gotMax = doBlend(d, enable = true, ZERO, ZERO, OP_MAX, ZERO, ZERO, OP_MAX, src, dst)
        println(f"  MAX with both factors ZERO -> (${gotMax.r},${gotMax.g},${gotMax.b},${gotMax.a})")
        utest.assert(gotMax == C(200, 200, 90, 180))
        println("  PASSED")
      }
    }

    utest.test("add_and_subtract_saturate_rather_than_wrap") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: saturation ---")
        val src = C(200, 200, 200, 200)
        val dst = C(200, 200, 200, 200)

        val add = doBlend(d, enable = true, ONE, ONE, OP_ADD, ONE, ONE, OP_ADD, src, dst)
        println(f"  200 + 200 -> ${add.r} (expect 255, wrap would give 145)")
        utest.assert(add == C(255, 255, 255, 255))

        val sub = doBlend(d, enable = true, ZERO, ONE, OP_SUBTRACT, ZERO, ONE, OP_SUBTRACT, src, dst)
        println(f"  0 - 200 -> ${sub.r} (expect 0, wrap would give 56)")
        utest.assert(sub == C(0, 0, 0, 0))
        println("  PASSED")
      }
    }

    // =========================================================================
    // The case every application actually uses, and the disabled path
    // =========================================================================

    utest.test("src_over_dst_alpha_compositing") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: SRC_ALPHA / ONE_MINUS_SRC_ALPHA (src-over) ---")
        // Half-transparent red over opaque blue: the canonical case, and the
        // one whose numeric answer is checkable by hand.
        val src = C(255, 0, 0, 128)
        val dst = C(0, 0, 255, 255)
        val got = doBlend(d, enable = true,
          SRC_ALPHA, ONE_MINUS_SRC_ALPHA, OP_ADD,
          ONE, ONE_MINUS_SRC_ALPHA, OP_ADD, src, dst)
        val exp = refBlend(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, OP_ADD,
                           ONE, ONE_MINUS_SRC_ALPHA, OP_ADD, src, dst, C(0, 0, 0, 0))
        println(f"  (${got.r},${got.g},${got.b},${got.a}) -- red ~128, blue ~127")
        utest.assert(got == exp)
        utest.assert(got.r == 128 && got.b == 127)
        println("  PASSED")
      }
    }

    utest.test("disabled_blend_passes_source_through_untouched") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: enable=0 is the identity ---")
        val src = C(200, 100, 50, 180)
        val dst = C(30, 200, 90, 120)
        // Configured with factors that would visibly change the result if the
        // enable gate leaked.
        val got = doBlend(d, enable = false,
          SRC_ALPHA, ONE_MINUS_SRC_ALPHA, OP_ADD, ONE, ZERO, OP_ADD, src, dst)
        println(f"  out=(${got.r},${got.g},${got.b},${got.a}) src=(${src.r},${src.g},${src.b},${src.a})")
        utest.assert(got == src)
        println("  PASSED")
      }
    }

    // =========================================================================
    // Cross-product sweep: every factor pair against every op
    // =========================================================================

    utest.test("factor_x_factor_x_op_cross_product_matches_reference") {
      simulate(new BorgBlendUnit) { d =>
        println("\n--- BorgBlend: 15 x 15 x 5 cross product ---")
        val src   = C(200, 100, 50, 180)
        val dst   = C(30, 200, 90, 120)
        val const = C(10, 20, 40, 220)
        var n = 0
        for ((_, sf) <- ALL_FACTORS; (_, df) <- ALL_FACTORS; (_, op) <- ALL_OPS) {
          val got = doBlend(d, enable = true, sf, df, op, sf, df, op, src, dst, const)
          val exp = refBlend(sf, df, op, sf, df, op, src, dst, const)
          if (got != exp)
            sys.error(s"sf=$sf df=$df op=$op: got $got expected $exp")
          n += 1
        }
        println(f"  $n configurations match the reference model")
        println("  PASSED")
      }
    }
  }
}
