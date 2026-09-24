// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import scala.collection.mutable.ArrayBuffer

/** Unit tests for BorgTileFlusher — one 16-word RGB565 burst per tile.
  *
  * The flusher reads all 16 tile-buffer entries ({R,G,B,Z}), converts each to a
  * single RGB565 halfword (depth dropped — TBR keeps Z on-chip only), and streams
  * the whole tile to SDRAM as one 16-word burst write (raster order).  These
  * tests mock the tile-buffer read port and play the role of the
  * MemoryController: present `waccept` per word, then `ready` at the end, while
  * collecting the streamed words to verify order and content.
  */
object BorgTileFlusherTests extends TestSuite {

  // ── Reference FP16 → RGB565, mirroring BorgTileFlusher.fp16ToUnorm ──
  def fp16ToUnorm(h: Int, bits: Int): Int = {
    val sign = (h >> 15) & 1
    val exp  = (h >> 10) & 0x1f
    val mant = h & 0x3ff
    val full = (1 << 10) | mant
    // round(v * 255), as ColorQuantize.quantize8, then the top `bits` bits.
    val n = 25 - exp
    val u8 = if (sign == 1 || exp == 0) 0 else if (exp >= 15) 255
             else math.min(255, (full * 255 + (1 << (n - 1))) >> n)
    u8 >> (8 - bits)
  }
  def rgb565(r: Int, g: Int, b: Int): Int =
    (fp16ToUnorm(r, 5) << 11) | (fp16ToUnorm(g, 6) << 5) | fp16ToUnorm(b, 5)

  // FP16 bit pattern for a finite float (round-to-nearest-even, no NaN/Inf).
  def f16(f: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(f)
    val sign = (bits >>> 16) & 0x8000
    val expF = ((bits >>> 23) & 0xff) - 127 + 15
    val mantF = bits & 0x7fffff
    if (f == 0.0f) sign
    else if (expF <= 0) sign  // flush subnormals to ±0 (good enough for the test)
    else if (expF >= 0x1f) sign | 0x7bff
    else sign | (expF << 10) | (mantF >> 13)
  }

  // Distinct, predictable colours per entry so a reorder/drop shows up.
  def entR(e: Int): Int = f16(e / 16.0f)          // 0 .. ~0.94
  def entG(e: Int): Int = f16((15 - e) / 16.0f)   // reverse ramp
  def entB(e: Int): Int = f16(0.5f)               // constant
  def entZ(e: Int): Int = 0x4000 + e              // dropped by the flusher
  def expWord(e: Int): Int = rgb565(entR(e), entG(e), entB(e))

  // Depth-flush test data: z = e/16, all exactly representable in FP16, so
  // quantize16's round(z * 65536) is exact -- expected UNORM16 is e * 4096
  // with no rounding slack to argue about.
  def dz(e: Int): Int    = f16(e / 16.0f)
  def expDz(e: Int): Int = e * 4096

  val tests = Tests {

    utest.test("depth flush streams a second UNORM16 burst to depthBase") {
      // Step 50 item 14: with hasDepthFlush, the flusher emits the colour
      // burst exactly as before and then a SECOND 16-word burst carrying the
      // tile's Z plane, quantized FP16 -> UNORM16, to a separate base
      // address. Verifies both bursts, their addresses, and the depth values.
      simulate(new BorgTileFlusher(16, 1, hasDepthFlush = true)) { dut =>
        var cycle = 0
        var pipe0: Option[Int] = None
        var pipe1: Option[Int] = None

        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          pipe1.foreach { i =>
            dut.io.read.data.foreach { s =>
              s.r.poke(entR(i).U); s.g.poke(entG(i).U)
              s.b.poke(entB(i).U); s.z.poke(dz(i).U)
            }
          }
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
          pipe1 = pipe0
          pipe0 = if (en) Some(idx) else None
          dut.clock.step()
          cycle += 1
          Predef.assert(cycle < 5000, "TIMEOUT")
        }

        dut.reset.poke(true.B); step(4)
        dut.reset.poke(false.B)
        dut.io.format.poke(FlushFormat.RGB565.U)
        dut.io.start.poke(false.B)
        dut.io.tileBase.poke(0.U)
        dut.io.depthBase.get.poke(0.U)
        dut.io.depthEn.get.poke(true.B)
        dut.io.gpuMem.ready.poke(false.B)
        dut.io.gpuMem.waccept.poke(false.B)
        dut.io.gpuMem.data.poke(0.U)
        step(2)

        dut.io.tileBase.poke(0x2000.U)
        dut.io.depthBase.get.poke(0x9000.U)
        dut.io.start.poke(true.B)
        step()
        dut.io.start.poke(false.B)

        /** Drive one 16-beat burst as the memory controller would, returning
          * (baseAddr, words). */
        def collectBurst(): (Int, Seq[Int]) = {
          var guard = 0
          while (!(dut.io.gpuMem.wr.peek().litToBoolean &&
                   dut.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 500) {
            step(); guard += 1
          }
          Predef.assert(guard < 500, "burst never started")
          val base = dut.io.gpuMem.addr.peek().litValue.toInt
          val words = ArrayBuffer[Int]()
          for (w <- 0 until 16) {
            Predef.assert(dut.io.gpuMem.wr.peek().litToBoolean, s"wr dropped at word $w")
            words += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
            if (w < 15) {
              dut.io.gpuMem.waccept.poke(true.B); step()
              dut.io.gpuMem.waccept.poke(false.B)
            }
          }
          dut.io.gpuMem.ready.poke(true.B); step()
          dut.io.gpuMem.ready.poke(false.B); step()
          (base, words.toSeq)
        }

        val (colourBase, colourWords) = collectBurst()
        Predef.assert(colourBase == 0x2000, s"colour burst base 0x${colourBase.toHexString} != 0x2000")
        for (w <- 0 until 16)
          Predef.assert(colourWords(w) == expWord(w),
            f"colour word $w%2d: got 0x${colourWords(w).toHexString} exp 0x${expWord(w).toHexString}")

        val (depthBase, depthWords) = collectBurst()
        Predef.assert(depthBase == 0x9000, s"depth burst base 0x${depthBase.toHexString} != 0x9000")
        var errors = 0
        for (w <- 0 until 16) {
          if (depthWords(w) != expDz(w)) {
            println(f"  depth word $w%2d: got 0x${depthWords(w).toHexString} exp 0x${expDz(w).toHexString}")
            errors += 1
          }
        }
        Predef.assert(errors == 0, s"$errors depth word mismatches")
        Predef.assert(!dut.io.busy.peek().litToBoolean, "flusher still busy after both bursts")
        println("[flusher] colour burst + 16-word UNORM16 depth burst both correct")
      }
    }

    utest.test("MSAA depth flush resolves to sample zero, not an average") {
      // The depth resolve mode, pinned by construction rather than by
      // reading the RTL: each of the four samples gets a DIFFERENT z, chosen
      // so that every plausible resolve mode produces a distinguishable
      // answer. At entry e:
      //   sample 0 = e/16      -> UNORM16 e*4096   (SAMPLE_ZERO, the one we want)
      //   sample 1 = 0.0       -> 0                (so MIN is not mistaken for it)
      //   sample 2 = 1.0       -> 65535            (so MAX is not)
      //   sample 3 = 0.5       -> 32768            (so AVERAGE is not)
      // VK_KHR_depth_stencil_resolve's four modes are exactly SAMPLE_ZERO /
      // AVERAGE / MIN / MAX, and v3dv -- the tile-based renderer closest to
      // this design -- supports only SAMPLE_ZERO for depth, which is what
      // this asserts. Colour is held identical across samples so the colour
      // burst stays the single-sample expectation and any failure here is
      // unambiguously about depth.
      simulate(new BorgTileFlusher(16, 4, hasDepthFlush = true)) { dut =>
        val decoyZ = Seq(f16(0.0f), f16(1.0f), f16(0.5f)) // samples 1..3
        var cycle = 0
        var pipe0: Option[Int] = None
        var pipe1: Option[Int] = None

        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          pipe1.foreach { i =>
            dut.io.read.data.zipWithIndex.foreach { case (s, smp) =>
              s.r.poke(entR(i).U); s.g.poke(entG(i).U); s.b.poke(entB(i).U)
              s.z.poke((if (smp == 0) dz(i) else decoyZ(smp - 1)).U)
            }
          }
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
          pipe1 = pipe0
          pipe0 = if (en) Some(idx) else None
          dut.clock.step()
          cycle += 1
          Predef.assert(cycle < 5000, "TIMEOUT")
        }

        dut.reset.poke(true.B); step(4)
        dut.reset.poke(false.B)
        dut.io.format.poke(FlushFormat.RGB565.U)
        dut.io.start.poke(false.B)
        dut.io.tileBase.poke(0.U)
        dut.io.depthBase.get.poke(0.U)
        dut.io.depthEn.get.poke(true.B)
        dut.io.gpuMem.ready.poke(false.B)
        dut.io.gpuMem.waccept.poke(false.B)
        dut.io.gpuMem.data.poke(0.U)
        step(2)

        dut.io.tileBase.poke(0x2000.U)
        dut.io.depthBase.get.poke(0x9000.U)
        dut.io.start.poke(true.B)
        step()
        dut.io.start.poke(false.B)

        def collectBurst(): (Int, Seq[Int]) = {
          var guard = 0
          while (!(dut.io.gpuMem.wr.peek().litToBoolean &&
                   dut.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 500) {
            step(); guard += 1
          }
          Predef.assert(guard < 500, "burst never started")
          val base = dut.io.gpuMem.addr.peek().litValue.toInt
          val words = ArrayBuffer[Int]()
          for (w <- 0 until 16) {
            Predef.assert(dut.io.gpuMem.wr.peek().litToBoolean, s"wr dropped at word $w")
            words += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
            if (w < 15) {
              dut.io.gpuMem.waccept.poke(true.B); step()
              dut.io.gpuMem.waccept.poke(false.B)
            }
          }
          dut.io.gpuMem.ready.poke(true.B); step()
          dut.io.gpuMem.ready.poke(false.B); step()
          (base, words.toSeq)
        }

        collectBurst() // colour burst; depth is what this test is about
        val (depthBase, depthWords) = collectBurst()
        Predef.assert(depthBase == 0x9000,
          s"depth burst base 0x${depthBase.toHexString} != 0x9000")

        var errors = 0
        for (w <- 0 until 16) {
          if (depthWords(w) != expDz(w)) {
            val diagnosis =
              if (depthWords(w) == 0) "  <- looks like MIN (sample 1)"
              else if (depthWords(w) == 65535) "  <- looks like MAX (sample 2)"
              else "  <- neither sample zero nor an obvious other mode"
            println(f"  depth word $w%2d: got 0x${depthWords(w).toHexString} " +
                    f"exp 0x${expDz(w).toHexString}$diagnosis")
            errors += 1
          }
        }
        Predef.assert(errors == 0,
          s"$errors depth words did not carry sample 0's value")
        println("[flusher] 4x MSAA depth burst carries sample 0 exactly " +
                "(VK_RESOLVE_MODE_SAMPLE_ZERO_BIT)")
      }
    }

    utest.test("depthEn=false skips the depth burst entirely") {
      // The runtime gate: a draw with no depth attachment bound must behave
      // exactly like the historical colour-only flush -- one burst, then idle.
      simulate(new BorgTileFlusher(16, 1, hasDepthFlush = true)) { dut =>
        var cycle = 0
        var pipe0: Option[Int] = None
        var pipe1: Option[Int] = None

        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          pipe1.foreach { i =>
            dut.io.read.data.foreach { s =>
              s.r.poke(entR(i).U); s.g.poke(entG(i).U)
              s.b.poke(entB(i).U); s.z.poke(dz(i).U)
            }
          }
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
          pipe1 = pipe0
          pipe0 = if (en) Some(idx) else None
          dut.clock.step()
          cycle += 1
          Predef.assert(cycle < 5000, "TIMEOUT")
        }

        dut.reset.poke(true.B); step(4)
        dut.reset.poke(false.B)
        dut.io.format.poke(FlushFormat.RGB565.U)
        dut.io.start.poke(false.B)
        dut.io.tileBase.poke(0x2000.U)
        dut.io.depthBase.get.poke(0x9000.U)
        dut.io.depthEn.get.poke(false.B)   // no depth attachment bound
        dut.io.gpuMem.ready.poke(false.B)
        dut.io.gpuMem.waccept.poke(false.B)
        dut.io.gpuMem.data.poke(0.U)
        step(2)

        dut.io.start.poke(true.B); step()
        dut.io.start.poke(false.B)

        var guard = 0
        while (!(dut.io.gpuMem.wr.peek().litToBoolean &&
                 dut.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 500) {
          step(); guard += 1
        }
        Predef.assert(guard < 500, "colour burst never started")
        for (w <- 0 until 16) {
          if (w < 15) {
            dut.io.gpuMem.waccept.poke(true.B); step()
            dut.io.gpuMem.waccept.poke(false.B)
          }
        }
        dut.io.gpuMem.ready.poke(true.B); step()
        dut.io.gpuMem.ready.poke(false.B); step()

        // No second burst may start, and the flusher must be idle.
        Predef.assert(!dut.io.gpuMem.wr.peek().litToBoolean,
          "a second burst started even though depthEn was false")
        Predef.assert(!dut.io.busy.peek().litToBoolean,
          "flusher still busy after the colour-only flush")
        step(20)
        Predef.assert(!dut.io.gpuMem.wr.peek().litToBoolean,
          "a delayed second burst started even though depthEn was false")
        println("[flusher] depthEn=false: colour burst only, no depth burst")
      }
    }

    utest.test("flusher streams 16 RGB565 words in one burst, correct order") {
      simulate(new BorgTileFlusher) { dut =>
        var cycle = 0

        // BorgTileBuffer has a 2-cycle read latency:
        //   cycle T  : flusher drives io.read.en=1, io.read.idx=N
        //   cycle T+1: SyncReadMem output valid; readDataHeld latches it
        //   cycle T+2: io.read.data = readDataHeld holds the result
        var pipe0: Option[Int] = None  // request issued this cycle
        var pipe1: Option[Int] = None  // request from 1 cycle ago (readDataHeld)

        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          pipe1.foreach { i =>
            // Every sample identical → the resolve average returns that value,
            // so the expected RGB565 is unchanged from the single-sample case.
            dut.io.read.data.foreach { s =>
              s.r.poke(entR(i).U)
              s.g.poke(entG(i).U)
              s.b.poke(entB(i).U)
              s.z.poke(entZ(i).U)
            }
          }
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
          pipe1 = pipe0
          pipe0 = if (en) Some(idx) else None
          dut.clock.step()
          cycle += 1
          Predef.assert(cycle < 5000, "TIMEOUT")
        }

        dut.reset.poke(true.B);  step(4)
        dut.reset.poke(false.B)
        dut.io.format.poke(FlushFormat.RGB565.U)
        dut.io.start.poke(false.B)
        dut.io.tileBase.poke(0.U)
        dut.io.gpuMem.ready.poke(false.B)
        dut.io.gpuMem.waccept.poke(false.B)
        dut.io.gpuMem.data.poke(0.U)
        step(2)

        // Trigger the flush.
        dut.io.tileBase.poke(0x2000.U)
        dut.io.start.poke(true.B)
        step()
        dut.io.start.poke(false.B)

        // Wait for the read-in phase to finish and the burst to start.
        var guard = 0
        while (!(dut.io.gpuMem.wr.peek().litToBoolean &&
                 dut.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 500) {
          step(); guard += 1
        }
        Predef.assert(guard < 500, "burst never started")
        Predef.assert(dut.io.gpuMem.addr.peek().litValue.toInt == 0x2000,
          "burst base address mismatch")
        val burstStart = cycle

        // Play the controller: collect the current word, pulse waccept to advance.
        val collected = ArrayBuffer[Int]()
        for (w <- 0 until 16) {
          Predef.assert(dut.io.gpuMem.wr.peek().litToBoolean, s"wr dropped at word $w")
          collected += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
          if (w < 15) {
            dut.io.gpuMem.waccept.poke(true.B)
            step()
            dut.io.gpuMem.waccept.poke(false.B)
          }
        }
        val burstCycles = cycle - burstStart

        // End the burst.
        dut.io.gpuMem.ready.poke(true.B)
        step()
        dut.io.gpuMem.ready.poke(false.B)
        step()
        Predef.assert(!dut.io.gpuMem.wr.peek().litToBoolean, "wr still high after ready")
        Predef.assert(!dut.io.busy.peek().litToBoolean, "flusher still busy after ready")

        // Verify all 16 RGB565 words, in order.
        var errors = 0
        for (w <- 0 until 16) {
          if (collected(w) != expWord(w)) {
            println(f"  word $w%2d: got 0x${collected(w).toHexString} exp 0x${expWord(w).toHexString}")
            errors += 1
          }
        }
        Predef.assert(errors == 0, s"$errors word mismatches in the tile burst")
        println(s"[flusher] 16-word RGB565 tile burst streamed in $burstCycles cycles, all words correct")
      }
    }

    // ── 4× MSAA resolve ───────────────────────────────────────────────────
    // Feed each pixel FOUR DIFFERENT sample values and check the flusher emits
    // their average.  Identical samples would pass trivially even if resolve
    // were a no-op that just read sample 0, so differing samples are the only
    // thing that actually proves the averaging path.
    utest.test("msaa 4x resolve averages the four samples") {
      simulate(new BorgTileFlusher(16, 4)) { dut =>
        var cycle = 0

        // Per-entry, per-sample colours. Sample s of entry e gets a distinct
        // grey so the average is a value none of the individual samples hold.
        def sampR(e: Int, s: Int): Int = f16((e * 4 + s) / 64.0f)
        def sampG(e: Int, s: Int): Int = f16(0.25f * s)      // 0, .25, .5, .75
        def sampB(e: Int, s: Int): Int = f16(0.5f)           // constant

        // Reference resolve: average in the 8-bit unorm domain, then truncate
        // to 5/6 bits — mirroring BorgTileFlusher.resolveChannel exactly.
        def resolve(vals: Seq[Int], bits: Int): Int = {
          val sum = vals.map(v => fp16ToUnorm(v, 8)).sum
          (sum >> 2) >> (8 - bits)
        }
        def expResolved(e: Int): Int = {
          val r = resolve((0 until 4).map(s => sampR(e, s)), 5)
          val g = resolve((0 until 4).map(s => sampG(e, s)), 6)
          val b = resolve((0 until 4).map(s => sampB(e, s)), 5)
          (r << 11) | (g << 5) | b
        }

        var pipe0: Option[Int] = None
        var pipe1: Option[Int] = None
        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          pipe1.foreach { i =>
            for (s <- 0 until 4) {
              dut.io.read.data(s).r.poke(sampR(i, s).U)
              dut.io.read.data(s).g.poke(sampG(i, s).U)
              dut.io.read.data(s).b.poke(sampB(i, s).U)
              dut.io.read.data(s).z.poke(entZ(i).U)
            }
          }
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
          pipe1 = pipe0
          pipe0 = if (en) Some(idx) else None
          dut.clock.step()
          cycle += 1
          Predef.assert(cycle < 5000, "TIMEOUT")
        }

        dut.reset.poke(true.B); step(4)
        dut.reset.poke(false.B)
        dut.io.format.poke(FlushFormat.RGB565.U)
        dut.io.start.poke(false.B)
        dut.io.tileBase.poke(0.U)
        dut.io.gpuMem.ready.poke(false.B)
        dut.io.gpuMem.waccept.poke(false.B)
        dut.io.gpuMem.data.poke(0.U)
        step(2)

        dut.io.tileBase.poke(0x3000.U)
        dut.io.start.poke(true.B)
        step()
        dut.io.start.poke(false.B)

        var guard = 0
        while (!(dut.io.gpuMem.wr.peek().litToBoolean &&
                 dut.io.gpuMem.wlen.peek().litValue.toInt == 16) && guard < 500) {
          step(); guard += 1
        }
        Predef.assert(guard < 500, "burst never started")

        val collected = ArrayBuffer[Int]()
        for (w <- 0 until 16) {
          collected += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
          if (w < 15) {
            dut.io.gpuMem.waccept.poke(true.B)
            step()
            dut.io.gpuMem.waccept.poke(false.B)
          }
        }
        dut.io.gpuMem.ready.poke(true.B); step()
        dut.io.gpuMem.ready.poke(false.B); step()

        var errors = 0
        for (w <- 0 until 16) {
          if (collected(w) != expResolved(w)) {
            println(f"  word $w%2d: got 0x${collected(w).toHexString} exp 0x${expResolved(w).toHexString}")
            errors += 1
          }
        }
        Predef.assert(errors == 0, s"$errors resolved-word mismatches")

        // Sanity: the resolve must NOT be equal to just reading sample 0,
        // otherwise this test would pass on a broken (no-op) resolve.
        val sample0Only = (0 until 16).map { e =>
          (fp16ToUnorm(sampR(e, 0), 5) << 11) |
          (fp16ToUnorm(sampG(e, 0), 6) << 5) | fp16ToUnorm(sampB(e, 0), 5)
        }
        Predef.assert(collected.toSeq != sample0Only,
          "resolved output equals sample 0 — averaging is not actually happening")
        println("[flusher] 4x MSAA resolve: 16 averaged RGB565 words correct, " +
                "and provably different from sample-0 passthrough")
      }
    }
    // ── 32-bit colour formats (R8G8B8A8_UNORM / B8G8R8A8_UNORM) ─────────────
    // Both mandatory COLOR_ATTACHMENT formats. A tile is 64 bytes, written as
    // two 16-halfword bursts (pixels 0..7 at tileBase, 8..15 at tileBase+32),
    // followed by the unchanged D16 depth burst. Every channel carries a
    // distinct per-pixel ramp and alpha differs per SAMPLE at 4x MSAA, so a
    // swapped byte, a dropped half, or alpha taken from one sample instead of
    // averaged all fail.
    def wideFlush(samples: Int, format: Int): Unit = {
      simulate(new BorgTileFlusher(16, samples, hasDepthFlush = true, hasAlpha = true)) { dut =>
        def sAlpha(e: Int, smp: Int): Int = (e * 13 + smp * 40) & 0xFF
        var cycle = 0
        var pipe0: Option[Int] = None
        var pipe1: Option[Int] = None
        def step(n: Int = 1): Unit = for (_ <- 0 until n) {
          pipe1.foreach { i =>
            dut.io.read.data.foreach { s =>
              s.r.poke(entR(i).U); s.g.poke(entG(i).U)
              s.b.poke(entB(i).U); s.z.poke(dz(i).U)
            }
            dut.io.alpha.get.zipWithIndex.foreach { case (a, smp) => a.poke(sAlpha(i, smp).U) }
          }
          val en  = dut.io.read.en.peek().litToBoolean
          val idx = dut.io.read.idx.peek().litValue.toInt
          pipe1 = pipe0
          pipe0 = if (en) Some(idx) else None
          dut.clock.step()
          cycle += 1
          Predef.assert(cycle < 10000, "TIMEOUT")
        }

        dut.reset.poke(true.B); step(4)
        dut.reset.poke(false.B)
        dut.io.start.poke(false.B)
        dut.io.format.poke(format.U)
        dut.io.tileBase.poke(0x2000.U)
        dut.io.depthBase.get.poke(0x9000.U)
        dut.io.depthEn.get.poke(true.B)
        dut.io.gpuMem.ready.poke(false.B)
        dut.io.gpuMem.waccept.poke(false.B)
        dut.io.gpuMem.data.poke(0.U)
        step(2)
        dut.io.start.poke(true.B); step(); dut.io.start.poke(false.B)

        val bursts = ArrayBuffer[(Int, Seq[Int])]()
        while (dut.io.busy.peek().litToBoolean) {
          if (dut.io.gpuMem.wr.peek().litToBoolean) {
            Predef.assert(dut.io.gpuMem.wlen.peek().litValue.toInt == 16, "burst length")
            val base = dut.io.gpuMem.addr.peek().litValue.toInt
            val words = ArrayBuffer[Int]()
            for (w <- 0 until 16) {
              words += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
              if (w < 15) { dut.io.gpuMem.waccept.poke(true.B); step(); dut.io.gpuMem.waccept.poke(false.B) }
            }
            dut.io.gpuMem.ready.poke(true.B); step()
            dut.io.gpuMem.ready.poke(false.B)
            bursts += ((base, words.toSeq))
          }
          step()
        }

        println(f"[flusher] samples=$samples format=$format: bursts at " +
          bursts.map(b => f"0x${b._1}%x").mkString(", "))
        Predef.assert(bursts.map(_._1) == Seq(0x2000, 0x2020, 0x9000),
          "expected two colour halves then the depth burst")
        // Reassemble the 64 colour bytes, little-endian halfwords.
        val bytes = bursts.take(2).flatMap(_._2).flatMap(h => Seq(h & 0xFF, (h >> 8) & 0xFF))
        var errors = 0
        for (e <- 0 until 16) {
          val r = fp16ToUnorm(entR(e), 8); val g = fp16ToUnorm(entG(e), 8)
          val b = fp16ToUnorm(entB(e), 8)
          val a = (0 until samples).map(sAlpha(e, _)).sum / samples
          val exp = if (format == FlushFormat.BGRA8) Seq(b, g, r, a) else Seq(r, g, b, a)
          val got = bytes.slice(4 * e, 4 * e + 4)
          if (got != exp) {
            println(s"  pixel $e: got $got expected $exp"); errors += 1
          }
        }
        Predef.assert(errors == 0, s"$errors pixel mismatches")
        for (w <- 0 until 16)
          Predef.assert(bursts(2)._2(w) == expDz(w), s"depth word $w")
      }
    }

    utest.test("rgba8 flush: two 8-pixel bursts, Vulkan byte order, then depth") {
      wideFlush(1, FlushFormat.RGBA8)
    }
    utest.test("bgra8 flush swaps R and B only") {
      wideFlush(1, FlushFormat.BGRA8)
    }
    utest.test("msaa 4x rgba8 flush averages alpha across samples") {
      wideFlush(4, FlushFormat.RGBA8)
    }
    utest.test("stencil flush follows depth as eight S8 halfword pairs") {
      // A stencil attachment used to be impossible: the plane never left the
      // chip. It now goes out after the depth burst, 16 bytes per tile, two
      // entries per halfword beat (entry 2k in the low byte). MSAA stores
      // sample 0 like depth; here samples differ so taking any other sample
      // would show.
      for (samples <- Seq(1, 4)) {
        simulate(new BorgTileFlusher(16, samples, hasDepthFlush = true, hasStencil = true)) { dut =>
          def sten(e: Int, smp: Int): Int = (e * 11 + 5 + smp * 64) & 0xFF
          var cycle = 0
          var pipe0: Option[Int] = None
          var pipe1: Option[Int] = None
          def step(n: Int = 1): Unit = for (_ <- 0 until n) {
            pipe1.foreach { i =>
              dut.io.read.data.foreach { s =>
                s.r.poke(entR(i).U); s.g.poke(entG(i).U); s.b.poke(entB(i).U); s.z.poke(dz(i).U)
              }
              dut.io.stencil.get.zipWithIndex.foreach { case (st, smp) => st.poke(sten(i, smp).U) }
            }
            val en  = dut.io.read.en.peek().litToBoolean
            val idx = dut.io.read.idx.peek().litValue.toInt
            pipe1 = pipe0
            pipe0 = if (en) Some(idx) else None
            dut.clock.step()
            cycle += 1
            Predef.assert(cycle < 10000, "TIMEOUT")
          }
          dut.reset.poke(true.B); step(4)
          dut.reset.poke(false.B)
          dut.io.format.poke(FlushFormat.RGB565.U)
          dut.io.start.poke(false.B)
          dut.io.tileBase.poke(0x2000.U)
          dut.io.depthBase.get.poke(0x9000.U); dut.io.depthEn.get.poke(true.B)
          dut.io.stencilBase.get.poke(0xA000.U); dut.io.stencilEn.get.poke(true.B)
          dut.io.gpuMem.ready.poke(false.B); dut.io.gpuMem.waccept.poke(false.B)
          dut.io.gpuMem.data.poke(0.U)
          step(2)
          dut.io.start.poke(true.B); step(); dut.io.start.poke(false.B)

          val bursts = ArrayBuffer[(Int, Seq[Int])]()
          while (dut.io.busy.peek().litToBoolean) {
            if (dut.io.gpuMem.wr.peek().litToBoolean) {
              val base = dut.io.gpuMem.addr.peek().litValue.toInt
              val wlen = dut.io.gpuMem.wlen.peek().litValue.toInt
              val words = ArrayBuffer[Int]()
              for (w <- 0 until wlen) {
                words += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
                if (w < wlen - 1) { dut.io.gpuMem.waccept.poke(true.B); step(); dut.io.gpuMem.waccept.poke(false.B) }
              }
              dut.io.gpuMem.ready.poke(true.B); step()
              dut.io.gpuMem.ready.poke(false.B)
              bursts += ((base, words.toSeq))
            }
            step()
          }
          println(f"[flusher] samples=$samples bursts: " + bursts.map(b => f"0x${b._1}%x/${b._2.length}").mkString(" "))
          Predef.assert(bursts.map(b => (b._1, b._2.length)) == Seq((0x2000, 16), (0x9000, 16), (0xA000, 8)))
          val bytes = bursts(2)._2.flatMap(h => Seq(h & 0xFF, h >> 8))
          for (e <- 0 until 16)
            Predef.assert(bytes(e) == sten(e, 0), s"stencil entry $e: ${bytes(e)} != ${sten(e, 0)}")
        }
      }
    }
    utest.test("fp32 depth stores as D16_UNORM or D32_SFLOAT") {
      // With FP32 tile depth the flusher converts per beat: D16 is exactly
      // round(z * 65535); D32 is the FP32 itself, 64 bytes per tile as two
      // 32-byte bursts, low halfword first.
      def zf(e: Int): Float = (e * 4093 + 17).toFloat / 65536.0f
      def zbits(e: Int): Long = java.lang.Float.floatToRawIntBits(zf(e)).toLong & 0xFFFFFFFFL
      for (d32 <- Seq(false, true)) {
        simulate(new BorgTileFlusher(16, 1, hasDepthFlush = true, zBits = 32)) { dut =>
          var cycle = 0
          var pipe0: Option[Int] = None
          var pipe1: Option[Int] = None
          def step(n: Int = 1): Unit = for (_ <- 0 until n) {
            pipe1.foreach { i =>
              dut.io.read.data.foreach { s =>
                s.r.poke(entR(i).U); s.g.poke(entG(i).U); s.b.poke(entB(i).U); s.z.poke(zbits(i).U)
              }
            }
            val en  = dut.io.read.en.peek().litToBoolean
            val idx = dut.io.read.idx.peek().litValue.toInt
            pipe1 = pipe0
            pipe0 = if (en) Some(idx) else None
            dut.clock.step()
            cycle += 1
            Predef.assert(cycle < 10000, "TIMEOUT")
          }
          dut.reset.poke(true.B); step(4)
          dut.reset.poke(false.B)
          dut.io.format.poke(FlushFormat.RGB565.U)
          dut.io.start.poke(false.B)
          dut.io.tileBase.poke(0x2000.U)
          dut.io.depthBase.get.poke(0x9000.U); dut.io.depthEn.get.poke(true.B)
          dut.io.depthD32.get.poke(d32.B)
          dut.io.gpuMem.ready.poke(false.B); dut.io.gpuMem.waccept.poke(false.B)
          dut.io.gpuMem.data.poke(0.U)
          step(2)
          dut.io.start.poke(true.B); step(); dut.io.start.poke(false.B)
          val bursts = ArrayBuffer[(Int, Seq[Int])]()
          while (dut.io.busy.peek().litToBoolean) {
            if (dut.io.gpuMem.wr.peek().litToBoolean) {
              val base = dut.io.gpuMem.addr.peek().litValue.toInt
              val wlen = dut.io.gpuMem.wlen.peek().litValue.toInt
              val words = ArrayBuffer[Int]()
              for (w <- 0 until wlen) {
                words += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
                if (w < wlen - 1) { dut.io.gpuMem.waccept.poke(true.B); step(); dut.io.gpuMem.waccept.poke(false.B) }
              }
              dut.io.gpuMem.ready.poke(true.B); step()
              dut.io.gpuMem.ready.poke(false.B)
              bursts += ((base, words.toSeq))
            }
            step()
          }
          val depth = bursts.filter(_._1 >= 0x9000)
          println(f"[flusher] fp32 depth, ${if (d32) "D32" else "D16"}: bursts " +
            depth.map(b => f"0x${b._1}%x/${b._2.length}").mkString(" "))
          if (d32) {
            Predef.assert(depth.map(_._1) == Seq(0x9000, 0x9020))
            val halves = depth.flatMap(_._2)
            for (e <- 0 until 16) {
              val got = (halves(2 * e).toLong) | (halves(2 * e + 1).toLong << 16)
              Predef.assert(got == zbits(e), f"D32 entry $e: 0x$got%08x != 0x${zbits(e)}%08x")
            }
          } else {
            Predef.assert(depth.map(_._1) == Seq(0x9000))
            for (e <- 0 until 16) {
              val want = math.round(zf(e).toDouble * 65535).toInt
              Predef.assert(depth.head._2(e) == want, s"D16 entry $e: ${depth.head._2(e)} != $want")
            }
          }
        }
      }
    }
    utest.test("per-sample store writes every sample to its own region") {
      // ATTACH_MS: a stored 4x attachment keeps every sample. Each sample gets
      // its own colour/depth/stencil region, one tile size apart, holding
      // that sample's value -- not the average, not sample 0. With msSample
      // given (msaaMultiPass) only that one sample's regions are written.
      for (given <- Seq(None, Some(2))) {
        simulate(new BorgTileFlusher(16, 4, hasDepthFlush = true, hasStencil = true)) { dut =>
          def cr(e: Int, smp: Int): Int = f16((e + 16 * smp) / 64.0f)
          def cz(e: Int, smp: Int): Int = f16((e + 16 * smp + 1) / 128.0f)
          def cs(e: Int, smp: Int): Int = (e * 3 + smp * 50 + 1) & 0xFF
          var cycle = 0
          var pipe0: Option[Int] = None
          var pipe1: Option[Int] = None
          def step(n: Int = 1): Unit = for (_ <- 0 until n) {
            pipe1.foreach { i =>
              dut.io.read.data.zipWithIndex.foreach { case (sd, smp) =>
                sd.r.poke(cr(i, smp).U); sd.g.poke(0.U); sd.b.poke(0.U); sd.z.poke(cz(i, smp).U)
              }
              dut.io.stencil.get.zipWithIndex.foreach { case (st, smp) => st.poke(cs(i, smp).U) }
            }
            val en  = dut.io.read.en.peek().litToBoolean
            val idx = dut.io.read.idx.peek().litValue.toInt
            pipe1 = pipe0
            pipe0 = if (en) Some(idx) else None
            dut.clock.step()
            cycle += 1
            Predef.assert(cycle < 40000, "TIMEOUT")
          }
          dut.reset.poke(true.B); step(4)
          dut.reset.poke(false.B)
          dut.io.format.poke(FlushFormat.RGB565.U)
          dut.io.start.poke(false.B)
          dut.io.tileBase.poke(0x2000.U)
          dut.io.depthBase.get.poke(0x9000.U); dut.io.depthEn.get.poke(true.B)
          dut.io.stencilBase.get.poke(0xA000.U); dut.io.stencilEn.get.poke(true.B)
          dut.io.msStore.get.poke(true.B)
          dut.io.msSample.get.valid.poke(given.isDefined.B)
          dut.io.msSample.get.bits.poke(given.getOrElse(0).U)
          dut.io.gpuMem.ready.poke(false.B); dut.io.gpuMem.waccept.poke(false.B)
          dut.io.gpuMem.data.poke(0.U)
          step(2)
          dut.io.start.poke(true.B); step(); dut.io.start.poke(false.B)
          val bursts = ArrayBuffer[(Int, Seq[Int])]()
          while (dut.io.busy.peek().litToBoolean) {
            if (dut.io.gpuMem.wr.peek().litToBoolean) {
              val base = dut.io.gpuMem.addr.peek().litValue.toInt
              val wlen = dut.io.gpuMem.wlen.peek().litValue.toInt
              val words = ArrayBuffer[Int]()
              for (w <- 0 until wlen) {
                words += (dut.io.gpuMem.wdata.peek().litValue.toInt & 0xFFFF)
                if (w < wlen - 1) { dut.io.gpuMem.waccept.poke(true.B); step(); dut.io.gpuMem.waccept.poke(false.B) }
              }
              dut.io.gpuMem.ready.poke(true.B); step()
              dut.io.gpuMem.ready.poke(false.B)
              bursts += ((base, words.toSeq))
            }
            step()
          }
          val smps = given.map(Seq(_)).getOrElse(0 until 4)
          println(f"[flusher] per-sample store, samples ${smps.mkString(",")}: bursts " +
            bursts.map(b => f"0x${b._1}%x").mkString(" "))
          Predef.assert(bursts.map(_._1) ==
            smps.flatMap(sm => Seq(0x2000 + 32 * sm, 0x9000 + 32 * sm, 0xA000 + 16 * sm)))
          for ((sm, k) <- smps.zipWithIndex) {
            val (colour, depth, sten) = (bursts(3 * k)._2, bursts(3 * k + 1)._2, bursts(3 * k + 2)._2)
            for (e <- 0 until 16) {
              Predef.assert((colour(e) >> 11) == fp16ToUnorm(cr(e, sm), 5), s"sample $sm entry $e colour")
              // cz = k/128 is exact in FP16 and quantize16 is round(z * 65536): k * 512.
              Predef.assert(depth(e) == (e + 16 * sm + 1) * 512, s"sample $sm entry $e depth ${depth(e)}")
              val sb = sten(e / 2) >> (8 * (e % 2)) & 0xFF
              Predef.assert(sb == cs(e, sm), s"sample $sm entry $e stencil $sb != ${cs(e, sm)}")
            }
          }
        }
      }
    }
  }
}
