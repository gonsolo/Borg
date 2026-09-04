// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Standalone correctness sweep for [[ColorQuantize]], independent of
  * BorgTileBuffer -- this is a pure bit-manipulation numeric unit and is
  * far cheaper to get right (and to debug) in isolation than embedded in the
  * full render pipeline. Verified before BorgTileBuffer ever calls it.
  */
class ColorQuantizeHarness extends Module {
  val fp16In  = IO(Input(UInt(16.W)))
  val u8Out   = IO(Output(UInt(8.W)))
  val u8In    = IO(Input(UInt(8.W)))
  val fp16Out = IO(Output(UInt(16.W)))

  u8Out   := ColorQuantize.quantize8(fp16In)
  fp16Out := ColorQuantize.dequantize8(u8In)
}

object ColorQuantizeTests extends TestSuite {
  import BorgTests.{floatToBits, bitsToFloat}

  val tests = Tests {

    utest.test("dequantize8_exhaustive_256_values") {
      simulate(new ColorQuantizeHarness) { dut =>
        for (u <- 0 until 256) {
          dut.u8In.poke(u.U)
          val gotBits = dut.fp16Out.peek().litValue
          val got     = bitsToFloat(gotBits, FloatConfig.FP16)
          val want    = u / 255.0f
          val err     = math.abs(got - want)
          // FP16 has ~2^-10 relative resolution near 1.0; the 65536-vs-65535
          // approximation error is ~1.5e-5, an order of magnitude below that
          // -- 2e-3 absolute is a generous, still-meaningful bound.
          utest.assert(err < 2e-3f)
        }
      }
    }

    utest.test("dequantize8_zero_and_max_exact") {
      simulate(new ColorQuantizeHarness) { dut =>
        dut.u8In.poke(0.U)
        utest.assert(dut.fp16Out.peek().litValue == 0)
        dut.u8In.poke(255.U)
        val got = bitsToFloat(dut.fp16Out.peek().litValue, FloatConfig.FP16)
        utest.assert(math.abs(got - 1.0f) < 1e-3f)
      }
    }

    utest.test("quantize8_matches_round_reference") {
      simulate(new ColorQuantizeHarness) { dut =>
        val samples = (0 to 1000).map(_ / 1000.0f) ++
          Seq(0.0f, 1.0f, 1.0f / 255, 254.5f / 255, 0.5f / 255, 0.9999f, 0.0001f)
        for (f <- samples) {
          val bits = floatToBits(f, FloatConfig.FP16)
          dut.fp16In.poke(bits.U(16.W))
          val got = dut.u8Out.peek().litValue.toInt
          // Reference computed from the ACTUAL fp16-rounded value (not the
          // original float), since that's what the hardware sees.
          val fp16Val = bitsToFloat(bits, FloatConfig.FP16)
          val want = math.round(math.max(0.0, math.min(1.0, fp16Val)) * 255).toInt
          // Allow +/-1 for the 256-vs-255 scale approximation right at
          // rounding boundaries; report the worst case seen.
          utest.assert(math.abs(got - want) <= 1)
        }
      }
    }

    utest.test("quantize8_clamps_out_of_range") {
      simulate(new ColorQuantizeHarness) { dut =>
        // Exactly 1.0 (exp=15,mant=0) and anything >1.0 must clamp to 255.
        dut.fp16In.poke(floatToBits(1.0f, FloatConfig.FP16).U(16.W))
        utest.assert(dut.u8Out.peek().litValue == 255)
        dut.fp16In.poke(floatToBits(2.0f, FloatConfig.FP16).U(16.W))
        utest.assert(dut.u8Out.peek().litValue == 255)
        // Negative must clamp to 0.
        dut.fp16In.poke(floatToBits(-0.5f, FloatConfig.FP16).U(16.W))
        utest.assert(dut.u8Out.peek().litValue == 0)
        // Zero and subnormals must be 0.
        dut.fp16In.poke(0.U(16.W))
        utest.assert(dut.u8Out.peek().litValue == 0)
      }
    }

    utest.test("round_trip_quantize_dequantize_stable") {
      // The property that actually matters for MSAA resolve: once a color has
      // been through the narrow store once, reading it back and writing it
      // again (e.g. a later triangle updating the same tile slot) must not
      // keep drifting far -- quantize8(dequantize8(u)) ~= u for every u.
      //
      // Not bit-exact: dequantize8's cheap u*257/65536 approximation of u/255
      // creates exactly one exact tie in the whole 256-value space (u=128 ->
      // 32896/65536 -> quantize8's round(value*256) sees precisely x.5 and
      // rounds up to 129). Fixing that exactly needs a real 11x8 multiplier
      // in quantize8 (255 is not a power of two); adding multiplier hardware
      // to chase a single sub-half-LSB tie in a deliberately lossy 8-bit
      // color path would spend real area on an imperceptible correction,
      // working against the entire point of this feature. +/-1 matches the
      // tolerance already accepted in quantize8_matches_round_reference.
      simulate(new ColorQuantizeHarness) { dut =>
        for (u <- 0 until 256) {
          dut.u8In.poke(u.U)
          val fp16Bits = dut.fp16Out.peek().litValue
          dut.fp16In.poke(fp16Bits.U(16.W))
          val back = dut.u8Out.peek().litValue.toInt
          utest.assert(math.abs(back - u) <= 1)
        }
      }
    }
  }
}
