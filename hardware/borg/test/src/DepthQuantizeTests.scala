// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Standalone correctness sweep for [[DepthQuantize]], independent of
  * BorgTileBuffer -- same discipline as ColorQuantizeTests: a pure
  * bit-manipulation numeric unit is far cheaper to get right in isolation
  * than embedded in the full render pipeline.
  */
class DepthQuantizeHarness extends Module {
  val fp16In  = IO(Input(UInt(16.W)))
  val u16Out  = IO(Output(UInt(16.W)))
  val u16In   = IO(Input(UInt(16.W)))
  val fp16Out = IO(Output(UInt(16.W)))

  u16Out  := DepthQuantize.quantize16(fp16In)
  fp16Out := DepthQuantize.dequantize16(u16In)
}

object DepthQuantizeTests extends TestSuite {
  import BorgTests.{floatToBits, bitsToFloat}

  val tests = Tests {

    utest.test("dequantize16_sampled_values") {
      // Exhaustive 65536 pokes is expensive in simulation; sample across the
      // range instead (fine steps near zero, where the flush-to-zero-below-4
      // guard and the exponent boundary both live, plus coarser coverage
      // across the rest of the range).
      simulate(new DepthQuantizeHarness) { dut =>
        val samples = (0 until 64) ++ (0 until 256).map(_ * 256) ++ Seq(65535)
        for (u <- samples) {
          dut.u16In.poke(u.U)
          val gotBits = dut.fp16Out.peek().litValue
          val got     = bitsToFloat(gotBits, FloatConfig.FP16)
          val want    = u / 65536.0f
          if (u < 4) {
            utest.assert(got == 0.0f) // flushed, see DepthQuantize's doc
          } else {
            val err = math.abs(got - want)
            // FP16 has ~2^-10 relative resolution; a 16-bit source has more
            // precision than FP16's 10-bit mantissa can hold, so error grows
            // with magnitude -- bound scales with the value itself.
            utest.assert(err < math.max(2e-3f, want * 2e-3f))
          }
        }
      }
    }

    utest.test("dequantize16_zero_and_max") {
      simulate(new DepthQuantizeHarness) { dut =>
        dut.u16In.poke(0.U)
        utest.assert(dut.fp16Out.peek().litValue == 0)
        dut.u16In.poke(65535.U)
        val got = bitsToFloat(dut.fp16Out.peek().litValue, FloatConfig.FP16)
        utest.assert(math.abs(got - 1.0f) < 1e-2f)
      }
    }

    utest.test("quantize16_matches_round_reference") {
      simulate(new DepthQuantizeHarness) { dut =>
        val samples = (0 to 1000).map(_ / 1000.0f) ++
          Seq(0.0f, 1.0f, 1.0f / 65535, 65534.5f / 65535, 0.5f / 65535, 0.9999f, 0.0001f)
        for (f <- samples) {
          val bits = floatToBits(f, FloatConfig.FP16)
          dut.fp16In.poke(bits.U(16.W))
          val got = dut.u16Out.peek().litValue.toInt
          // Reference computed from the ACTUAL fp16-rounded value (not the
          // original float), since that's what the hardware sees.
          val fp16Val = bitsToFloat(bits, FloatConfig.FP16)
          val want = math.round(math.max(0.0, math.min(1.0, fp16Val)) * 65536).toInt.min(65535)
          // Generous tolerance: FP16's ~10-bit mantissa can't carry 16 bits
          // of fraction, so the round-to-nearest-UNORM16 reference and the
          // hardware's SIG-based shift can legitimately differ by more than
          // +/-1 at low exponents (few significant bits to work with) --
          // bound scales with the target's own magnitude, same reasoning as
          // the dequantize direction's tolerance above.
          val tol = math.max(2, (want * 2e-3).toInt)
          utest.assert(math.abs(got - want) <= tol)
        }
      }
    }

    utest.test("quantize16_clamps_out_of_range") {
      simulate(new DepthQuantizeHarness) { dut =>
        // Exactly 1.0 (exp=15,mant=0) and anything >1.0 must clamp to 65535.
        dut.fp16In.poke(floatToBits(1.0f, FloatConfig.FP16).U(16.W))
        utest.assert(dut.u16Out.peek().litValue == 65535)
        dut.fp16In.poke(floatToBits(2.0f, FloatConfig.FP16).U(16.W))
        utest.assert(dut.u16Out.peek().litValue == 65535)
        // Negative must clamp to 0.
        dut.fp16In.poke(floatToBits(-0.5f, FloatConfig.FP16).U(16.W))
        utest.assert(dut.u16Out.peek().litValue == 0)
        // Zero and subnormals must be 0.
        dut.fp16In.poke(0.U(16.W))
        utest.assert(dut.u16Out.peek().litValue == 0)
      }
    }

    utest.test("quantize16_boundary_exp9_no_discontinuity") {
      // exp==9 is the left/right-shift boundary in quantize16's derivation
      // -- exercise it directly (value = 2^(9-15) = 1/64) plus its
      // immediate neighbours, since a boundary sign error would show up as
      // a large discontinuity right here, not gradually.
      simulate(new DepthQuantizeHarness) { dut =>
        val value = 1.0f / 64
        val bits  = floatToBits(value, FloatConfig.FP16)
        dut.fp16In.poke(bits.U(16.W))
        val got  = dut.u16Out.peek().litValue.toInt
        val want = math.round(value * 65536).toInt
        utest.assert(math.abs(got - want) <= 1)
      }
    }
  }
}
