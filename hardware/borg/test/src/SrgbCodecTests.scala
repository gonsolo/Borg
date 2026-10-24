// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._

class SrgbCodecIO extends Bundle {
  val h = Input(UInt(16.W)); val c = Input(UInt(8.W)); val enc = Output(UInt(8.W)); val dec = Output(UInt(16.W))
}

class SrgbCodecDut extends Module {
  val io = IO(new SrgbCodecIO)
  io.enc := SrgbCodec.encode(io.h)
  io.dec := SrgbCodec.decode(io.c)
}

/** The encoder against exact rounding for every non-negative finite FP16 value; decode then
  * encode returns every code. */
object SrgbCodecTests extends TestSuite {
  def srgb(l: Double) = if (l <= 0.0031308) 12.92 * l else 1.055 * math.pow(l, 1 / 2.4) - 0.055
  val tests = Tests {
    utest.test("encode_is_exact_and_decode_round_trips") {
      simulate(new SrgbCodecDut) { dut =>
        var bad = 0
        for (h <- 0 until 0x7C00) {
          dut.io.h.poke(h.U)
          val l = java.lang.Float.float16ToFloat(h.toShort).toDouble
          val want = if (h < 0x400) 0 else math.round(srgb(math.min(l, 1.0)) * 255).toInt
          val got = dut.io.enc.peek().litValue.toInt
          if (got != want) { if (bad < 5) println(f"  h=0x$h%04x ($l%e): $got, expect $want"); bad += 1 }
        }
        for (c <- 0 until 256) {
          dut.io.c.poke(c.U); val d = dut.io.dec.peek().litValue
          dut.io.h.poke(d.U)
          utest.assert(dut.io.enc.peek().litValue.toInt == c)
        }
        println(s"  encode: $bad of ${0x7C00} FP16 values off")
        utest.assert(bad == 0)
      }
    }
  }
}
