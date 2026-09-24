// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** BorgTileLoader: a tile's attachments back from DRAM (loadOp = LOAD).
  *
  * The harness plays DRAM with the exact layouts BorgTileFlusher writes and
  * checks every tile-buffer write the loader makes: the decoded colour,
  * depth and stencil, and the clear values for aspects that are not loaded.
  */
object BorgTileLoaderTests extends TestSuite {

  val C_BASE = 0x1000; val Z_BASE = 0x2000; val S_BASE = 0x3000
  val CLEAR = (0x1111, 0x2222, 0x3333, 0x7BFF)   // r, g, b, z as FP16 bits
  val CLEAR_A = 0x44; val CLEAR_S = 0x55

  /** dequantize8, mirrored bit for bit (u*257/65536, mantissa truncated). */
  def deq8(u: Int): Int = {
    val n = (u << 8) | u
    if (n == 0) 0 else {
      val msb = 31 - Integer.numberOfLeadingZeros(n)
      val exp = (msb - 1) & 31
      val mant = ((n << (15 - msb)) >> 5) & 0x3ff
      (exp << 10) | mant
    }
  }
  def f16ToFloat(b: Int): Float = {
    val e = (b >> 10) & 0x1f; val m = b & 0x3ff
    if (e == 0) m.toFloat / (1 << 24) else (1.0f + m / 1024.0f) * math.pow(2.0, e - 15).toFloat
  }

  case class Entry(r: Int, g: Int, b: Int, z: Int, a: Int, s: Int)

  /** Run one tile load against `mem` (byte address -> 32-bit word). */
  def load(d: BorgTileLoader, mem: Map[Int, BigInt], color: Boolean, depth: Boolean,
           stencil: Boolean, format: Int): Map[Int, Entry] = {
    d.io.aspects.color.poke(color.B); d.io.aspects.depth.poke(depth.B)
    d.io.aspects.stencil.poke(stencil.B)
    d.io.format.poke(format.U)
    d.io.colorBase.poke(C_BASE.U); d.io.depthBase.get.poke(Z_BASE.U); d.io.stencilBase.get.poke(S_BASE.U)
    d.io.clearColor.r.poke(CLEAR._1.U); d.io.clearColor.g.poke(CLEAR._2.U)
    d.io.clearColor.b.poke(CLEAR._3.U); d.io.clearColor.z.poke(CLEAR._4.U)
    d.io.clearAlpha.poke(CLEAR_A.U); d.io.clearStencil.poke(CLEAR_S.U)
    d.io.gpuMem.ready.poke(false.B); d.io.gpuMem.waccept.poke(false.B)
    d.io.start.poke(true.B); d.clock.step(1); d.io.start.poke(false.B)
    val got = scala.collection.mutable.Map[Int, Entry]()
    var wd = 0
    while (d.io.busy.peek().litToBoolean && wd < 2000) {
      val rd = d.io.gpuMem.req.peek().litToBoolean
      if (rd) d.io.gpuMem.data.poke(mem.getOrElse(d.io.gpuMem.addr.peek().litValue.toInt, BigInt(0)).U)
      d.io.gpuMem.ready.poke(rd.B)
      if (d.io.write.en.peek().litToBoolean) {
        val w = d.io.write
        got(w.idx.peek().litValue.toInt) = Entry(w.data.r.peek().litValue.toInt,
          w.data.g.peek().litValue.toInt, w.data.b.peek().litValue.toInt,
          w.data.z.peek().litValue.toInt, w.alpha.peek().litValue.toInt,
          w.stencil.peek().litValue.toInt)
      }
      d.clock.step(1); wd += 1
    }
    d.io.gpuMem.ready.poke(false.B)
    utest.assert(wd < 2000)
    got.toMap
  }

  // Distinct per-entry values, so a swapped entry, byte or half shows up.
  def r8(e: Int) = (e * 17 + 3) & 0xff
  def g8(e: Int) = (e * 29 + 7) & 0xff
  def b8(e: Int) = (e * 43 + 11) & 0xff
  def a8(e: Int) = (e * 7 + 200) & 0xff
  def d16(e: Int) = e * 4096 + 100
  def s8(e: Int) = (e * 13 + 1) & 0xff

  def depthAndStencil: Map[Int, BigInt] =
    (0 until 16 by 2).map(e => (Z_BASE + 2 * e) -> (BigInt(d16(e)) | (BigInt(d16(e + 1)) << 16))).toMap ++
    (0 until 16 by 4).map(e => (S_BASE + e) ->
      (0 until 4).map(i => BigInt(s8(e + i)) << (8 * i)).sum).toMap

  val tests = Tests {

    utest.test("rgb565_depth_and_stencil_load_back_exactly") {
      simulate(new BorgTileLoader) { d =>
        // RGB565 packs two pixels per word; 5/6-bit channels expand to UNORM8
        // by bit replication -- what quantizes back to the same 5/6/5 bits.
        def px(e: Int) = ((r8(e) >> 3) << 11) | ((g8(e) >> 2) << 5) | (b8(e) >> 3)
        val mem = (0 until 16 by 2).map(e => (C_BASE + 2 * e) -> (BigInt(px(e)) | (BigInt(px(e + 1)) << 16))).toMap ++
                  depthAndStencil
        val got = load(d, mem, color = true, depth = true, stencil = true, FlushFormat.RGB565)
        utest.assert(got.keySet == (0 until 16).toSet)
        for (e <- 0 until 16) {
          val r5 = r8(e) >> 3; val g6 = g8(e) >> 2; val b5 = b8(e) >> 3
          val er = deq8((r5 << 3) | (r5 >> 2)); val eg = deq8((g6 << 2) | (g6 >> 4)); val eb = deq8((b5 << 3) | (b5 >> 2))
          val x = got(e)
          utest.assert((x.r, x.g, x.b) == ((er, eg, eb)))
          utest.assert(x.a == 0xff)                        // no alpha in RGB565: opaque
          utest.assert(math.abs(f16ToFloat(x.z) - d16(e) / 65535.0f) < 1e-3f)
          utest.assert(x.s == s8(e))
        }
        println("  RGB565 + D16 + S8: all 16 entries correct")
      }
    }

    utest.test("rgba8_colour_only_keeps_the_clear_depth_and_stencil") {
      simulate(new BorgTileLoader) { d =>
        val mem = (0 until 16).map(e => (C_BASE + 4 * e) ->
          BigInt(r8(e) | (g8(e) << 8) | (b8(e) << 16) | (a8(e).toLong << 24))).toMap ++ depthAndStencil
        val got = load(d, mem, color = true, depth = false, stencil = false, FlushFormat.RGBA8)
        for (e <- 0 until 16) {
          val x = got(e)
          utest.assert((x.r, x.g, x.b, x.a) == ((deq8(r8(e)), deq8(g8(e)), deq8(b8(e)), a8(e))))
          utest.assert(x.z == CLEAR._4 && x.s == CLEAR_S)   // loadOp CLEAR for these
        }
        println("  RGBA8 colour load, depth/stencil left at their clear values")
      }
    }

    utest.test("bgra8_swaps_red_and_blue_and_depth_only_keeps_the_clear_colour") {
      simulate(new BorgTileLoader) { d =>
        val mem = (0 until 16).map(e => (C_BASE + 4 * e) ->
          BigInt(b8(e) | (g8(e) << 8) | (r8(e) << 16) | (a8(e).toLong << 24))).toMap ++ depthAndStencil
        val bgra = load(d, mem, color = true, depth = false, stencil = false, FlushFormat.BGRA8)
        for (e <- 0 until 16) utest.assert((bgra(e).r, bgra(e).b) == ((deq8(r8(e)), deq8(b8(e)))))
        val zOnly = load(d, mem, color = false, depth = true, stencil = false, FlushFormat.BGRA8)
        for (e <- 0 until 16) {
          val x = zOnly(e)
          utest.assert((x.r, x.g, x.b, x.a) == ((CLEAR._1, CLEAR._2, CLEAR._3, CLEAR_A)))
          utest.assert(math.abs(f16ToFloat(x.z) - d16(e) / 65535.0f) < 1e-3f)
        }
        println("  BGRA8 byte order; depth-only load keeps the clear colour")
      }
    }
  }
}
