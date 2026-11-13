// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3.{Module, fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

/** One clock for both sides: the descriptor, the line fetches and the pixels' places. */
class MigScanoutRig extends Module {
  val io = chisel3.IO(new MigScanoutIO)
  val s  = Module(new MigScanout(0x800))
  io <> s.io
  s.io.pixClock := clock
  s.io.pixReset := reset.asBool
}

object MigScanoutTests extends TestSuite {
  // Linear: a row every `pitch` bytes. Tiled: Borg's 4x4 tiles of 64 bytes, a row of tiles every
  // `pitch` bytes, a tile's rows 16 bytes apart.
  def picture(tiled: Boolean): Unit =
      simulate(new MigScanoutRig) { dut =>
        val (w, h, base) = (8, 480, 0x10000L)
        val pitch = if (tiled) (w / 4) * 64L else 64L
        def at(x: Int, y: Int): Long =
          if (tiled) base + (y >> 2) * pitch + (x >> 2) * 64 + (y & 3) * 16 + (x & 3) * 4 else base + y * pitch + 4 * x
        val rnd   = new scala.util.Random(5)
        val lines = scala.collection.mutable.Map[Long, BigInt]().withDefaultValue(BigInt(0))
        def put(byte: Long, word: Long): Unit = {
          val l = byte >> 4; val sh = 32 * ((byte >> 2) & 3).toInt
          lines(l) = lines(l) & ~(BigInt(0xffffffffL) << sh) | (BigInt(word) << sh)
        }
        def pixel(x: Int, y: Int): Long = (0xA0L | x) << 16 | (y & 0xffL) << 8 | (x * 16 + (y & 15))
        put(0x800, base); put(0x804, pitch); put(0x808, w | h.toLong << 16); put(0x80C, if (tiled) 5 else 1)
        for (y <- 0 until 4; x <- 0 until w) put(at(x, y), pixel(x, y))

        val m = dut.io.mig
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        m.init_calib_complete.poke(true.B)
        m.app_wdf_rdy.poke(true.B)
        val rq = scala.collection.mutable.Queue[(Long, BigInt)]()
        var cycle = 0L
        var (hc, vc, frame, checked) = (0, 0, 0, 0)
        val x0 = (640 - w) / 2
        while (frame < 1 || vc < 3) {
          val rdy = rnd.nextInt(3) != 0
          m.app_rdy.poke(rdy.B)
          val due = rq.headOption.exists(_._1 <= cycle)
          m.app_rd_data_valid.poke(due.B)
          m.app_rd_data.poke((if (due) rq.dequeue()._2 else BigInt(0)).U(128.W))
          if (rdy && m.app_en.peek().litToBoolean) {
            assert(m.app_cmd.peek().litValue == 1)
            val a = m.app_addr.peek().litValue.toLong
            rq.enqueue((cycle + 8 + rnd.nextInt(12), lines(a >> 3)))
          }
          if (dut.io.tick.peek().litToBoolean) {
            if (frame == 1 && hc < 640) {
              val rgb  = dut.io.rgb.peek().litValue.toLong
              val want = if (hc >= x0 && hc < x0 + w) pixel(hc - x0, vc) else 0L
              assert(rgb == want)
              checked += 1
            }
            hc += 1
            if (hc == 800) { hc = 0; vc += 1; if (vc == 525) { vc = 0; frame += 1 } }
          }
          dut.clock.step()
          cycle += 1
        }
        assert(checked == 3 * 640)
      }

  val tests = Tests {
    test("a_centred_bgra8_picture_reaches_the_pixels") { picture(tiled = false) }
    test("a_tiled_picture_reaches_the_pixels_in_rows") { picture(tiled = true) }
  }
}
