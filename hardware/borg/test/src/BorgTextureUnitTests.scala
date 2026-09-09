// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Unit tests for BorgTextureUnit (Step 25.3e).
  *
  * Tests the 2-word DRAM texel fetch in complete isolation.  GpuMemIO is
  * driven directly — no BorgShaderDispatcher or BorgRasterizer context needed.
  *
  * Coverage:
  *   - B-first read order (offset +4 before offset +0) — Morton-stability
  *   - Correct address computation: baseAddr + (mortonIndex << 3)
  *   - Correct R/G/B unpacking from the two 32-bit words
  *   - done pulses for exactly one cycle then deasserts
  *   - No req asserted while idle (gpuMem port is quiet between fetches)
  *   - Second fetch after done works correctly (no stale state)
  *   - DRAM stall: gpuMem.ready de-asserted for multiple cycles before response
  *   - start ignored while a fetch is already in progress (no restart glitch)
  */
/** Probe for the coordinate conversion, which has no module of its own.
  *
  * It lives in BorgShaderDispatcher's datapath, not BorgTextureUnit's, so the
  * texture-unit tests below -- which poke fracU/fracV directly -- never
  * elaborate it. That gap let a width bug (`(sig >> n)(15,0)` on an 11-bit
  * operand) reach the full build. Hence a direct probe.
  */
class Fixed88ProbeIO extends Bundle {
  val fp16  = Input(UInt(16.W))
  val fixed = Output(UInt(16.W))
}

class Fixed88Probe extends Module {
  val io = IO(new Fixed88ProbeIO)
  io.fixed := Fp16ToFixed88(io.fp16)
}

/** Probe for the address-mode wrapping, which is a pure function used by
  * both the dispatcher (base coordinate) and the texture unit (neighbours). */
class AddrModeProbeIO extends Bundle {
  val raw      = Input(UInt(8.W))
  val log2Dim  = Input(UInt(4.W))
  val mode     = Input(UInt(2.W))
  val coord    = Output(UInt(8.W))
  val isBorder = Output(Bool())
}

class AddrModeProbe extends Module {
  val io = IO(new AddrModeProbeIO)
  val (c, b) = TexAddressMode(io.raw, io.log2Dim, io.mode)
  io.coord    := c
  io.isBorder := b
}

object BorgTextureUnitTests extends TestSuite {

  // Convenience: compute expected byte address for a Morton index
  def bAddr(base: Int, idx: Int): Int = base + (idx << 3) + 4  // B word: offset +4
  def rgAddr(base: Int, idx: Int): Int = base + (idx << 3)      // RG word: offset +0

  /** Reset the DUT and idle for a couple of cycles. */
  def reset(d: BorgTextureUnit): Unit = {
    // Bilinear operands, when the build has them. NEAREST by default so every
    // pre-existing test in this file keeps its original meaning.
    d.io.bilinear.foreach { b =>
      b.enable.poke(false.B)
      b.u8.poke(0.U); b.v8.poke(0.U)
      b.fracU.poke(0.U); b.fracV.poke(0.U)
      b.log2Dim.poke(0.U)
      // CLAMP_TO_EDGE is the reset mode and the historical behaviour.
      b.addrModeU.poke(TexAddressMode.CLAMP_TO_EDGE.U)
      b.addrModeV.poke(TexAddressMode.CLAMP_TO_EDGE.U)
      b.border.poke(0.U)
    }
    d.io.start.poke(false.B)
    d.io.texConfig.en.poke(false.B)
    d.io.texConfig.mortonIndex.poke(0.U)
    d.io.texConfig.baseAddr.poke(0.U)
    d.io.gpuMem.data.poke(0.U)
    d.io.gpuMem.ready.poke(false.B)
    d.reset.poke(true.B)
    d.clock.step(2)
    d.reset.poke(false.B)
    d.clock.step(1)
  }

  /** Drive a single DRAM read response.
    * Asserts ready for one cycle with the given data word.
    */
  def respondDram(d: BorgTextureUnit, data: Long): Unit = {
    d.io.gpuMem.data.poke(data.U)
    d.io.gpuMem.ready.poke(true.B)
    d.clock.step(1)
    d.io.gpuMem.ready.poke(false.B)
    d.io.gpuMem.data.poke(0.U)
  }

  /** Run a complete fetch: start pulse → B read → RG read → done cycle.
    * Returns (r, g, b) from fragColor on the done cycle.
    * Accepts optional stall cycles before each DRAM response.
    */
  def runFetch(
    d: BorgTextureUnit,
    base: Int, idx: Int,
    bWord: Long, rgWord: Long,
    stallB: Int = 0, stallRG: Int = 0
  ): (Int, Int, Int) = {
    // Configure texConfig
    d.io.texConfig.en.poke(true.B)
    d.io.texConfig.baseAddr.poke(base.U)
    d.io.texConfig.mortonIndex.poke(idx.U)

    // Pulse start
    d.io.start.poke(true.B)
    d.clock.step(1)
    d.io.start.poke(false.B)

    // Wait for B-word read (sReadB): optional stall
    d.clock.step(stallB)
    val addrB = d.io.gpuMem.addr.peek().litValue.toInt
    utest.assert(d.io.gpuMem.req.peek().litToBoolean)  // req must be asserted in sReadB
    utest.assert(addrB == bAddr(base, idx))             // B word: offset +4

    respondDram(d, bWord)

    // Wait for RG-word read (sReadRG): optional stall
    d.clock.step(stallRG)
    val addrRG = d.io.gpuMem.addr.peek().litValue.toInt
    utest.assert(d.io.gpuMem.req.peek().litToBoolean)  // req must be asserted in sReadRG
    utest.assert(addrRG == rgAddr(base, idx))            // RG word: offset +0

    respondDram(d, rgWord)

    // sDone: done should pulse this cycle
    val done = d.io.done.peek().litToBoolean
    val r    = d.io.fragColor.r.peek().litValue.toInt
    val g    = d.io.fragColor.g.peek().litValue.toInt
    val b    = d.io.fragColor.b.peek().litValue.toInt
    utest.assert(done)   // done must be high in sDone
    d.clock.step(1)
    utest.assert(!d.io.done.peek().litToBoolean)  // done must deassert after sDone
    (r, g, b)
  }

  val tests = Tests {

    // =========================================================================
    // Address computation: B word is at offset +4, RG word at offset +0
    // =========================================================================

    utest.test("b_word_address_is_morton_plus4") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: b_word_address_is_morton_plus4 ---")
        reset(d)

        val base = 0x0200
        val idx  = 0x003
        // Expected B addr = 0x0200 + (3 << 3) + 4 = 0x0200 + 24 + 4 = 0x021C
        // Expected RG addr = 0x0200 + (3 << 3)     = 0x0200 + 24     = 0x0218

        d.io.texConfig.en.poke(true.B)
        d.io.texConfig.baseAddr.poke(base.U)
        d.io.texConfig.mortonIndex.poke(idx.U)
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // In sReadB: check address
        val addrB = d.io.gpuMem.addr.peek().litValue.toInt
        val reqB  = d.io.gpuMem.req.peek().litToBoolean
        println(f"  sReadB: addr=0x${addrB.toHexString}, req=$reqB (expect 0x${bAddr(base, idx).toHexString})")
        utest.assert(reqB)
        utest.assert(addrB == bAddr(base, idx))

        respondDram(d, 0x00005555L)

        // In sReadRG: check address
        val addrRG = d.io.gpuMem.addr.peek().litValue.toInt
        println(f"  sReadRG: addr=0x${addrRG.toHexString} (expect 0x${rgAddr(base, idx).toHexString})")
        utest.assert(addrRG == rgAddr(base, idx))
        println("  PASSED")
      }
    }

    // =========================================================================
    // Correct R/G/B unpacking from the two 32-bit words
    // =========================================================================

    utest.test("rgb_unpacking_correct") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: rgb_unpacking_correct ---")
        reset(d)

        // Word 0 (B): low 16 = B=0xBBBB
        // Word 1 (RG): low 16 = R=0x1234, high 16 = G=0x5678
        val bWord  = 0x0000BBBBL
        val rgWord = 0x56781234L
        val (r, g, b) = runFetch(d, base = 0x0100, idx = 0x002, bWord = bWord, rgWord = rgWord)

        println(f"  fragColor: R=0x${r.toHexString}, G=0x${g.toHexString}, B=0x${b.toHexString}")
        utest.assert(r == 0x1234)
        utest.assert(g == 0x5678)
        utest.assert(b == 0xBBBB)
        println("  PASSED")
      }
    }

    // =========================================================================
    // done pulses exactly one cycle
    // =========================================================================

    utest.test("done_pulses_one_cycle") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: done_pulses_one_cycle ---")
        reset(d)

        runFetch(d, base = 0x0080, idx = 0x001, bWord = 0x00001111L, rgWord = 0x22223333L)
        // runFetch already checks done asserts then deasserts; just assert idle after
        utest.assert(!d.io.done.peek().litToBoolean)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        println("  done deasserted, req quiet ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // No req when idle
    // =========================================================================

    utest.test("req_quiet_when_idle") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: req_quiet_when_idle ---")
        reset(d)

        // Without any start pulse, req must stay low
        d.clock.step(5)
        utest.assert(!d.io.gpuMem.req.peek().litToBoolean)
        utest.assert(!d.io.done.peek().litToBoolean)
        println("  req=false, done=false after 5 idle cycles ✓")
        println("  PASSED")
      }
    }

    // =========================================================================
    // DRAM stall: ready de-asserted for multiple cycles before response
    // =========================================================================

    utest.test("dram_stall_multiple_cycles") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: dram_stall_multiple_cycles ---")
        reset(d)

        // 3-cycle stall before each response
        val (r, g, b) = runFetch(
          d, base = 0x0300, idx = 0x004,
          bWord  = 0x0000AAAAL,
          rgWord = 0xBBBBCCCCL,
          stallB = 3, stallRG = 3
        )
        println(f"  fragColor: R=0x${r.toHexString}, G=0x${g.toHexString}, B=0x${b.toHexString}")
        utest.assert(r == 0xCCCC)
        utest.assert(g == 0xBBBB)
        utest.assert(b == 0xAAAA)
        println("  PASSED")
      }
    }

    // =========================================================================
    // Second fetch works after first completes (no stale state)
    // =========================================================================

    utest.test("second_fetch_after_done") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: second_fetch_after_done ---")
        reset(d)

        // First fetch
        val (r1, g1, b1) = runFetch(d, base = 0x0100, idx = 0x001,
          bWord = 0x00001111L, rgWord = 0x22223333L)
        println(f"  Fetch 1: R=0x${r1.toHexString}, G=0x${g1.toHexString}, B=0x${b1.toHexString}")
        utest.assert(r1 == 0x3333 && g1 == 0x2222 && b1 == 0x1111)

        // Second fetch with different values
        val (r2, g2, b2) = runFetch(d, base = 0x0400, idx = 0x007,
          bWord = 0x0000DDDDL, rgWord = 0xEEEEFFFFL)
        println(f"  Fetch 2: R=0x${r2.toHexString}, G=0x${g2.toHexString}, B=0x${b2.toHexString}")
        utest.assert(r2 == 0xFFFF && g2 == 0xEEEE && b2 == 0xDDDD)
        println("  PASSED")
      }
    }

    // =========================================================================
    // start ignored mid-fetch (no restart / address corruption)
    // =========================================================================

    utest.test("start_ignored_during_fetch") {
      simulate(new BorgTextureUnit) { d =>
        println("\n--- BorgTextureUnit: start_ignored_during_fetch ---")
        reset(d)

        val base = 0x0200
        val idx  = 0x002
        d.io.texConfig.en.poke(true.B)
        d.io.texConfig.baseAddr.poke(base.U)
        d.io.texConfig.mortonIndex.poke(idx.U)

        // Start the fetch
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // While in sReadB, poke start again — must not restart
        d.io.start.poke(true.B)
        d.clock.step(1)
        d.io.start.poke(false.B)

        // Address should still be the original B address, not a new one
        val addrB = d.io.gpuMem.addr.peek().litValue.toInt
        println(f"  Mid-fetch addr=0x${addrB.toHexString} (expect 0x${bAddr(base, idx).toHexString})")
        utest.assert(addrB == bAddr(base, idx))

        // Complete the fetch normally
        respondDram(d, 0x00005555L)
        respondDram(d, 0x22221111L)
        val done = d.io.done.peek().litToBoolean
        utest.assert(done)
        println("  No restart glitch; fetch completed normally ✓")
        println("  PASSED")
      }
    }


    // =========================================================================
    // Bilinear filtering (VK_FILTER_LINEAR) -- core Vulkan, no feature bit.
    // =========================================================================

    val BILIN = true

    /** FP16 bits for a small positive float (finite normals only). */
    def f16(f: Float): Int = {
      val h = java.lang.Float.floatToIntBits(f)
      val sign = (h >>> 16) & 0x8000
      val expF = ((h >>> 23) & 0xff) - 127 + 15
      val mantF = h & 0x7fffff
      if (f == 0.0f) sign
      else if (expF <= 0) sign
      else if (expF >= 0x1f) sign | 0x7bff
      else sign | (expF << 10) | (mantF >> 13)
    }
    def f16ToFloat(bits: Int): Float = {
      val exp = (bits >> 10) & 0x1f
      val mant = bits & 0x3ff
      val mag = if (exp == 0) mant.toFloat / (1 << 24)
                else (1.0f + mant.toFloat / 1024.0f) * math.pow(2.0, exp - 15).toFloat
      if ((bits & 0x8000) != 0) -mag else mag
    }

    /** Serve one filtered sample: 4 taps x 2 reads, answering each read with
      * the value the address maps to. Returns the addresses touched, in order,
      * so a test can assert on the tap footprint itself. */
    def runFiltered(d: BorgTextureUnit, texel: Map[Int, (Int, Int, Int)],
                    base: Int): Seq[Int] = {
      var addrs = Vector.empty[Int]
      d.io.start.poke(true.B)
      d.clock.step(1)
      d.io.start.poke(false.B)
      var guard = 0
      while (!d.io.done.peek().litToBoolean && guard < 200) {
        if (d.io.gpuMem.req.peek().litToBoolean) {
          val a = d.io.gpuMem.addr.peek().litValue.toInt
          addrs = addrs :+ a
          val texelAddr = a & ~4
          val (r, g, b) = texel.getOrElse(texelAddr, (0, 0, 0))
          // Layout: word +4 is B, word +0 is {G[31:16], R[15:0]}.
          val data = if ((a & 4) != 0) BigInt(b) else (BigInt(g) << 16) | BigInt(r)
          d.io.gpuMem.data.poke(data.U)
          d.io.gpuMem.ready.poke(true.B)
        } else {
          d.io.gpuMem.ready.poke(false.B)
        }
        d.clock.step(1)
        guard += 1
      }
      d.io.gpuMem.ready.poke(false.B)
      utest.assert(guard < 200)
      addrs
    }

    utest.test("nearest_is_untouched_by_the_filtering_hardware") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: filter off ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.texConfig.mortonIndex.poke(0.U)
        // enable stays false: exactly two reads, and the raw FP16 texel comes
        // back bit-for-bit with no quantize/dequantize round trip.
        val texel = Map(0 -> (f16(1.0f), f16(0.5f), f16(0.25f)))
        val addrs = runFiltered(d, texel, 0)
        println(f"  reads=${addrs.length} (expect 2), addrs=${addrs.mkString(",")}")
        utest.assert(addrs.length == 2)
        utest.assert(d.io.fragColor.r.peek().litValue.toInt == f16(1.0f))
        utest.assert(d.io.fragColor.g.peek().litValue.toInt == f16(0.5f))
        utest.assert(d.io.fragColor.b.peek().litValue.toInt == f16(0.25f))
        println("  PASSED")
      }
    }

    utest.test("filtered_sample_fetches_the_four_neighbours") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: 2x2 tap footprint ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.bilinear.get.enable.poke(true.B)
        d.io.bilinear.get.u8.poke(1.U)
        d.io.bilinear.get.v8.poke(1.U)
        d.io.bilinear.get.log2Dim.poke(3.U)     // 8x8 texture, so 2 and 2 are valid
        d.io.bilinear.get.fracU.poke(0.U)
        d.io.bilinear.get.fracV.poke(0.U)

        val addrs = runFiltered(d, Map.empty, 0)
        // Four taps, two reads each, and the addresses must be the Morton
        // codes of (1,1) (2,1) (1,2) (2,2).
        def morton(x: Int, y: Int): Int =
          (0 until 8).map(i => ((x >> i) & 1) << (2 * i) | ((y >> i) & 1) << (2 * i + 1)).sum
        val expected = Seq((1, 1), (2, 1), (1, 2), (2, 2)).map(p => morton(p._1, p._2) << 3)
        val touched = addrs.map(_ & ~4).distinct
        println(f"  reads=${addrs.length} (expect 8), texels=${touched.mkString(",")} expect ${expected.mkString(",")}")
        utest.assert(addrs.length == 8)
        utest.assert(touched == expected)
        println("  PASSED")
      }
    }

    utest.test("frac_zero_returns_the_base_texel_exactly") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: frac 0 is the base texel ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.bilinear.get.enable.poke(true.B)
        d.io.bilinear.get.u8.poke(0.U); d.io.bilinear.get.v8.poke(0.U)
        d.io.bilinear.get.log2Dim.poke(3.U)
        d.io.bilinear.get.fracU.poke(0.U); d.io.bilinear.get.fracV.poke(0.U)

        // Base texel white, every neighbour black: with both fractions 0 the
        // filter must land entirely on the base tap. A weight sign error or a
        // swapped lerp order shows up immediately here.
        val texel = Map(0 -> (f16(1.0f), f16(1.0f), f16(1.0f)))
        runFiltered(d, texel, 0)
        val r = f16ToFloat(d.io.fragColor.r.peek().litValue.toInt)
        println(f"  r = $r%.3f (expect 1.0)")
        utest.assert(math.abs(r - 1.0f) < 0.01f)
        println("  PASSED")
      }
    }

    utest.test("frac_half_averages_the_two_horizontal_neighbours") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: horizontal blend ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.bilinear.get.enable.poke(true.B)
        d.io.bilinear.get.u8.poke(0.U); d.io.bilinear.get.v8.poke(0.U)
        d.io.bilinear.get.log2Dim.poke(3.U)
        d.io.bilinear.get.fracU.poke(128.U)   // halfway across
        d.io.bilinear.get.fracV.poke(0.U)

        def morton(x: Int, y: Int): Int =
          (0 until 8).map(i => ((x >> i) & 1) << (2 * i) | ((y >> i) & 1) << (2 * i + 1)).sum
        // (0,0) white, (1,0) black -> halfway should be ~0.5. fracV=0 keeps
        // the lower row out of it entirely, so this isolates the u lerp.
        val texel = Map(
          (morton(0, 0) << 3) -> (f16(1.0f), f16(1.0f), f16(1.0f)),
          (morton(1, 0) << 3) -> (0, 0, 0))
        runFiltered(d, texel, 0)
        val r = f16ToFloat(d.io.fragColor.r.peek().litValue.toInt)
        println(f"  r = $r%.3f (expect ~0.5)")
        utest.assert(math.abs(r - 0.5f) < 0.02f)
        println("  PASSED")
      }
    }

    utest.test("frac_half_in_both_axes_averages_all_four") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: full 2x2 average ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.bilinear.get.enable.poke(true.B)
        d.io.bilinear.get.u8.poke(0.U); d.io.bilinear.get.v8.poke(0.U)
        d.io.bilinear.get.log2Dim.poke(3.U)
        d.io.bilinear.get.fracU.poke(128.U)
        d.io.bilinear.get.fracV.poke(128.U)

        def morton(x: Int, y: Int): Int =
          (0 until 8).map(i => ((x >> i) & 1) << (2 * i) | ((y >> i) & 1) << (2 * i + 1)).sum
        // Three white, one black -> 0.75. This is the check that the v lerp
        // actually happens: a version that only lerped in u would give 0.5.
        val texel = Map(
          (morton(0, 0) << 3) -> (f16(1.0f), 0, 0),
          (morton(1, 0) << 3) -> (f16(1.0f), 0, 0),
          (morton(0, 1) << 3) -> (f16(1.0f), 0, 0),
          (morton(1, 1) << 3) -> (0, 0, 0))
        runFiltered(d, texel, 0)
        val r = f16ToFloat(d.io.fragColor.r.peek().litValue.toInt)
        println(f"  r = $r%.3f (expect ~0.75; 0.5 would mean no v lerp)")
        utest.assert(math.abs(r - 0.75f) < 0.02f)
        println("  PASSED")
      }
    }

    utest.test("neighbour_taps_clamp_at_the_texture_edge") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: edge clamp ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.bilinear.get.enable.poke(true.B)
        d.io.bilinear.get.u8.poke(7.U); d.io.bilinear.get.v8.poke(7.U)
        d.io.bilinear.get.log2Dim.poke(3.U)   // 8x8: 7 is the last valid index
        d.io.bilinear.get.fracU.poke(255.U); d.io.bilinear.get.fracV.poke(255.U)

        // u+1 and v+1 are past the edge. Unclamped, Morton would set a bit the
        // 8-wide addressing never carries and the read would land in
        // unpopulated memory -- black fringing along two edges of every
        // texture. All four taps must collapse onto (7,7).
        val addrs = runFiltered(d, Map.empty, 0)
        val touched = addrs.map(_ & ~4).distinct
        println(f"  distinct texels touched = ${touched.length} (expect 1 -- all clamped to (7,7))")
        utest.assert(touched.length == 1)
        println("  PASSED")
      }
    }

    utest.test("fp16_to_8dot8_splits_integer_and_fraction") {
      simulate(new Fixed88Probe) { d =>
        println("\n--- Fp16ToFixed88 ---")
        def f16(f: Float): Int = {
          val h = java.lang.Float.floatToIntBits(f)
          val sign = (h >>> 16) & 0x8000
          val expF = ((h >>> 23) & 0xff) - 127 + 15
          val mantF = h & 0x7fffff
          if (f == 0.0f) sign
          else if (expF <= 0) sign
          else if (expF >= 0x1f) sign | 0x7bff
          else sign | (expF << 10) | (mantF >> 13)
        }
        def conv(f: Float): (Int, Int) = {
          d.io.fp16.poke(f16(f).U)
          d.clock.step(1)
          val v = d.io.fixed.peek().litValue.toInt
          (v >> 8, v & 0xFF)
        }

        // Spans both shift directions: values below 1.0 shift right, values
        // at and above 2.0 shift left, and 1.x is the boundary. That boundary
        // is where the 11-bit-operand width bug lived.
        for ((v, ei, ef) <- Seq(
              (0.0f, 0, 0), (0.5f, 0, 128), (0.25f, 0, 64), (0.75f, 0, 192),
              (1.0f, 1, 0), (1.5f, 1, 128), (2.0f, 2, 0), (2.25f, 2, 64),
              (7.5f, 7, 128), (63.5f, 63, 128), (200.0f, 200, 0))) {
          val (i, f) = conv(v)
          println(f"  $v%8.3f -> int=$i%3d frac=$f%3d (expect $ei/$ef)")
          utest.assert(i == ei && f == ef)
        }

        // The integer half must agree with Fp16ToUint8, which the nearest
        // path uses -- if they ever disagree, a filtered sample and an
        // unfiltered one would name different base texels.
        utest.assert(conv(255.9f)._1 == 255)
        // Negative and out-of-range clamp the same way Fp16ToUint8 does.
        d.io.fp16.poke(f16(-1.0f).U); d.clock.step(1)
        utest.assert(d.io.fixed.peek().litValue.toInt == 0)
        println("  clamping matches the nearest path")
        println("  PASSED")
      }
    }

    utest.test("address_modes_wrap_clamp_and_mirror") {
      simulate(new AddrModeProbe) { d =>
        println("\n--- TexAddressMode ---")
        def at(raw: Int, mode: Int, log2Dim: Int = 3): (Int, Boolean) = {
          d.io.raw.poke(raw.U); d.io.mode.poke(mode.U); d.io.log2Dim.poke(log2Dim.U)
          d.clock.step(1)
          (d.io.coord.peek().litValue.toInt, d.io.isBorder.peek().litToBoolean)
        }
        // 8-wide texture: valid indices 0..7.
        val M = TexAddressMode

        // CLAMP_TO_EDGE is the reset mode and must be byte-for-byte what the
        // old ClampTexCoord did, or every existing render moves.
        for ((raw, exp) <- Seq((0, 0), (7, 7), (8, 7), (200, 7)))
          utest.assert(at(raw, M.CLAMP_TO_EDGE)._1 == exp)
        println("  CLAMP_TO_EDGE matches the historical clamp")

        // REPEAT tiles: 8 -> 0, 9 -> 1, 15 -> 7, 16 -> 0.
        for ((raw, exp) <- Seq((0, 0), (7, 7), (8, 0), (9, 1), (15, 7), (16, 0)))
          utest.assert(at(raw, M.REPEAT)._1 == exp)
        println("  REPEAT tiles on the power-of-two boundary")

        // MIRRORED_REPEAT folds: 8 -> 7, 9 -> 6, 15 -> 0, 16 -> 0 (next period).
        for ((raw, exp) <- Seq((0, 0), (7, 7), (8, 7), (9, 6), (15, 0), (16, 0)))
          utest.assert(at(raw, M.MIRRORED_REPEAT)._1 == exp)
        println("  MIRRORED_REPEAT reverses each repeat")

        // CLAMP_TO_BORDER only flags; in range it behaves like clamp.
        utest.assert(at(3, M.CLAMP_TO_BORDER) == (3, false))
        utest.assert(at(8, M.CLAMP_TO_BORDER)._2)
        utest.assert(!at(7, M.CLAMP_TO_BORDER)._2)
        println("  CLAMP_TO_BORDER flags only outside the texture")

        // log2Dim == 0 keeps its historical "unsized, don't clamp" meaning in
        // every mode -- existing call sites depend on it.
        for (mode <- Seq(M.REPEAT, M.MIRRORED_REPEAT, M.CLAMP_TO_EDGE, M.CLAMP_TO_BORDER))
          utest.assert(at(200, mode, log2Dim = 0)._1 == 200)
        println("  log2Dim=0 still means unsized in every mode")
        println("  PASSED")
      }
    }

    utest.test("repeat_makes_the_neighbour_tap_wrap_to_the_far_edge") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: REPEAT neighbour ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.bilinear.get.enable.poke(true.B)
        d.io.bilinear.get.u8.poke(7.U); d.io.bilinear.get.v8.poke(0.U)
        d.io.bilinear.get.log2Dim.poke(3.U)
        d.io.bilinear.get.fracU.poke(128.U); d.io.bilinear.get.fracV.poke(0.U)
        d.io.bilinear.get.addrModeU.poke(TexAddressMode.REPEAT.U)
        d.io.bilinear.get.addrModeV.poke(TexAddressMode.REPEAT.U)

        def morton(x: Int, y: Int): Int =
          (0 until 8).map(i => ((x >> i) & 1) << (2 * i) | ((y >> i) & 1) << (2 * i + 1)).sum
        // Sampling across the right edge of a tiling texture: the second tap
        // must come from column 0, not a clamped copy of column 7. Under
        // CLAMP_TO_EDGE both taps would be texel (7,0) and the seam would
        // smear instead of wrapping.
        val addrs = runFiltered(d, Map.empty, 0)
        val touched = addrs.map(_ & ~4).distinct
        println(f"  texels=${touched.mkString(",")} expect ${morton(7,0) << 3} and ${morton(0,0) << 3}")
        utest.assert(touched.contains(morton(7, 0) << 3))
        utest.assert(touched.contains(morton(0, 0) << 3))
        println("  PASSED")
      }
    }

    utest.test("clamp_to_border_substitutes_without_reading_memory") {
      simulate(new BorgTextureUnit(BILIN)) { d =>
        println("\n--- BorgTextureUnit: CLAMP_TO_BORDER ---")
        reset(d)
        d.io.texConfig.baseAddr.poke(0.U)
        d.io.bilinear.get.enable.poke(true.B)
        d.io.bilinear.get.u8.poke(7.U); d.io.bilinear.get.v8.poke(7.U)
        d.io.bilinear.get.log2Dim.poke(3.U)
        d.io.bilinear.get.fracU.poke(128.U); d.io.bilinear.get.fracV.poke(128.U)
        d.io.bilinear.get.addrModeU.poke(TexAddressMode.CLAMP_TO_BORDER.U)
        d.io.bilinear.get.addrModeV.poke(TexAddressMode.CLAMP_TO_BORDER.U)
        d.io.bilinear.get.border.poke(BorderColor.OPAQUE_WHITE.U)

        def morton(x: Int, y: Int): Int =
          (0 until 8).map(i => ((x >> i) & 1) << (2 * i) | ((y >> i) & 1) << (2 * i + 1)).sum
        // Only tap 0 is inside; the other three are border. Exactly one texel
        // may be read -- the border taps must not issue an access, since
        // their address is outside the texture's allocation.
        val texel = Map((morton(7, 7) << 3) -> (0, 0, 0))
        val addrs = runFiltered(d, texel, 0)
        val touched = addrs.map(_ & ~4).distinct
        println(f"  texels read = ${touched.length} (expect 1 -- three taps are border)")
        utest.assert(touched.length == 1)
        // Base texel black, three quarters white border, weights at the
        // centre -> roughly 3/4 white.
        val r = f16ToFloat(d.io.fragColor.r.peek().litValue.toInt)
        println(f"  r = $r%.3f (expect ~0.75)")
        utest.assert(math.abs(r - 0.75f) < 0.02f)
        println("  PASSED")
      }
    }
  }
}
