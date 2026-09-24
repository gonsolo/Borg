// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** IO bundle for [[BorgTileFlusher]].
  *
  * Directions are from the flusher's perspective (master).
  */
/** Colour attachment formats the flusher can write (FLUSH_FORMAT.format).
  * Encodings are the register's; 3 is reserved and behaves as RGB565. */
object FlushFormat {
  val RGB565 = 0   // VK_FORMAT_R5G6B5_UNORM_PACK16: 2 bytes/pixel, 32 bytes/tile
  val RGBA8  = 1   // VK_FORMAT_R8G8B8A8_UNORM:      4 bytes/pixel, 64 bytes/tile
  val BGRA8  = 2   // VK_FORMAT_B8G8R8A8_UNORM:      4 bytes/pixel, 64 bytes/tile
  /** 4 bytes per pixel: the tile is twice as large in memory. */
  def isWide(f: UInt): Bool = f === RGBA8.U || f === BGRA8.U
}

class BorgTileFlusherIO(val dataBits: Int = 16, val samples: Int = 1,
                        val hasDepthFlush: Boolean = false,
                        val hasAlpha: Boolean = false,
                        val hasStencil: Boolean = false,
                        val zBits: Int = 16) extends Bundle {
  // Trigger interface
  val start     = Input(Bool())    // one-cycle pulse to begin flush
  val busy      = Output(Bool())   // high while flushing

  // Tile SRAM read port (flusher drives idx/en, reads data)
  val read      = new TileReadIO(dataBits, samples, zBits)

  // DRAM write port
  val gpuMem    = new GpuMemIO

  // Tile base address: absolute DRAM byte address of this tile's region.
  // Layout (RGB565): 16 entries × 2 bytes = 32 bytes per tile.
  //   word[i] = RGB565(entry[i])   (R[15:11] | G[10:5] | B[4:0])
  // Layout (RGBA8/BGRA8): 16 entries × 4 bytes = 64 bytes per tile, the
  // Vulkan byte order (R8G8B8A8: byte 0 = R ... byte 3 = A).
  // tileBase = fbBase + tile_index * (32 or 64)
  //   where tile_index = (ty >> 2) * tiles_per_row + (tx >> 2)
  val tileBase  = Input(UInt(25.W))

  // Colour attachment format, see [[FlushFormat]]. Sampled at the start pulse.
  val format    = Input(UInt(2.W))
  // Destination alpha per sample, valid alongside `read.data` (the tile
  // buffer's alpha plane shares its read index and latency). Absent when the
  // build has no alpha plane, in which case the 32-bit formats write opaque.
  val alpha     = if (hasAlpha) Some(Input(Vec(samples, UInt(8.W)))) else None

  // Stencil attachment write-out (S8_UINT, 16 bytes per tile), after the
  // depth burst. Without it the stencil plane never left the chip, so no
  // stencil attachment could be stored for a later render pass or read back.
  // The plane data comes with the same timing as `read.data`; at MSAA the
  // stored value is sample 0's, the same resolve depth uses.
  val stencil     = if (hasStencil) Some(Input(Vec(samples, UInt(8.W)))) else None
  val stencilBase = if (hasStencil) Some(Input(UInt(25.W))) else None
  val stencilEn   = if (hasStencil) Some(Input(Bool())) else None

  // Depth attachment format (DEPTH_FORMAT): false = D16_UNORM (16 x 2 bytes
  // per tile), true = D32_SFLOAT (16 x 4 bytes, two bursts). D32 needs FP32
  // tile depth, so it exists only with zBits = 32.
  val depthD32 = if (hasDepthFlush && zBits == 32) Some(Input(Bool())) else None

  // Optional depth-attachment write-out (Step 50 item 14 groundwork; absent
  // unless hasDepthFlush). Historically Z was NEVER written to DRAM -- the
  // TBR renders each tile fully on-chip, so nothing downstream needed the
  // depth value, which is exactly why `D16_UNORM` (a mandatory Vulkan
  // format) had no path to exist as a real, readable image. These ports
  // give the flusher a second burst target for the tile's Z plane,
  // quantized FP16 -> UNORM16 via DepthQuantize.
  //
  // Layout mirrors the colour burst: 16 entries x 2 bytes = 32 bytes per
  // tile, word[i] = UNORM16(entry[i].z). depthBase is the absolute DRAM
  // byte address of this tile's depth region (firmware computes it the same
  // way as tileBase, from its own depth-buffer base).
  val depthBase = if (hasDepthFlush) Some(Input(UInt(25.W))) else None
  // Runtime gate: even in a hasDepthFlush build, a draw with no depth
  // attachment bound skips the second burst entirely (straight to sIdle),
  // costing only the state check.
  val depthEn   = if (hasDepthFlush) Some(Input(Bool())) else None
}

/** BorgTileFlusher -- bulk DMA from tile SRAM to DRAM, one burst per tile.
  *
  * RGB565 streams all 16 tile-buffer entries as ONE 16-halfword burst. The
  * 32-bit formats (RGBA8/BGRA8) need 32 halfwords per tile; they are written
  * as TWO 16-halfword bursts of 8 pixels each, reusing the same 16-entry
  * staging vector. That keeps the burst length -- and with it the link's
  * burst buffer (LinkParams.maxBurst = 16) -- unchanged, at the cost of a
  * second fill/burst round per tile only in the wide formats.
  *
  * Phases (sFill/sBurst run twice per tile in the wide formats):
  *   sFill  -- read the burst's tile entries (pipelined), convert to the
  *             attachment format, stash into rgbVec.  The 2-cycle TileBuffer
  *             read latency is hidden by issuing one read per cycle and
  *             capturing 3 cycles later.
  *   sBurst -- stream the 16 staged halfwords as one burst.  rgbVec is
  *             a plain register read (no latency), so the word for the next beat
  *             is ready the cycle after `waccept` -- exactly when the controller
  *             samples it.  No burst-time read race.
  *
  * io.read.en/idx are REGISTERED outputs (set one cycle before they appear on
  * the wire) so arcilator can evaluate them from the state array without
  * circular combinational dependencies through the tile instance.
  *
  * Fill pipeline (mirrors the 3-cycle issue->data latency):
  *   cycle N:   readEnReg := true (register set; wire goes high next cycle)
  *   cycle N+1: io.read.en=1 visible; SyncReadMem latches address
  *   cycle N+2: SyncReadMem output travels through readDataHeld (cycle 2 of 2)
  *   cycle N+3: io.read.data valid; capture RGB565 into rgbVec
  */
/** @param hasDepthFlush Adds a second DRAM burst that writes the tile's Z
  *                      plane (quantized FP16 -> UNORM16 via [[DepthQuantize]])
  *                      to a separate depth-buffer region, for `D16_UNORM`
  *                      depth-attachment support. Default false: a disabled
  *                      build elaborates no zVec, no depthBaseReg and no
  *                      extra ports, and is cycle-identical to before this
  *                      parameter existed -- same compile-time-branch
  *                      discipline the `samples` parameter already uses
  *                      below. (Precisely: the sBurstZ and sBurstS enum
  *                      values and their switch arms ARE still elaborated,
  *                      because Chisel's switch macro won't accept a
  *                      conditionally-present is() arm -- but nothing
  *                      transitions into them, so they are unreachable and
  *                      fold away in synthesis.)
  *
  *                      Deliberately folded into this module rather than
  *                      built as a separate depth flusher: sFill already
  *                      reads all 16 tile entries for the colour burst, and
  *                      each entry carries its own `.z` alongside r/g/b, so
  *                      the depth path costs one extra staging vector and one
  *                      extra burst state -- NOT a second read pass over the
  *                      tile SRAM, which would double the flush's read
  *                      traffic for no reason.
  *
  *                      At samples>1 the depth flush resolves by taking
  *                      SAMPLE 0. Averaging really is the wrong operation for
  *                      depth, but "take sample zero" is the settled answer,
  *                      not an open question: Vulkan's depth/stencil resolve
  *                      (VK_KHR_depth_stencil_resolve, core since 1.2)
  *                      defines SAMPLE_ZERO / AVERAGE / MIN / MAX, every Mesa
  *                      driver advertises SAMPLE_ZERO, and v3dv -- the
  *                      tile-based renderer closest to this design --
  *                      supports ONLY VK_RESOLVE_MODE_SAMPLE_ZERO_BIT for
  *                      depth and stencil. V3D's tile-store hardware spells
  *                      the same three-way choice as `decimate_mode`
  *                      (ALL_SAMPLES / 4X-average / SAMPLE_0) and routes the
  *                      depth resolve to SAMPLE_0.
  *
  *                      Until 2026-09-15 a `require` made hasDepthFlush +
  *                      samples>1 a build error, because the MSAA branch
  *                      below genuinely had no depth path -- the staging,
  *                      the sBurst -> sBurstZ hand-off and the sBurstZ arm
  *                      all existed only in the samples==1 branch. Both
  *                      branches now carry it; the MSAA one stages
  *                      `pendingSamples(0).z` as each pixel's colour resolve
  *                      retires, which is the same sample-zero rule the
  *                      single-sample branch gets for free from
  *                      `io.read.data(0).z`.
  *
  *                      What is still NOT supported is a true multisampled
  *                      depth ATTACHMENT (V3D's ALL_SAMPLES mode): storing
  *                      every sample's Z costs 4x the depth memory and a
  *                      wider burst. Sample-zero resolve is what the resolve
  *                      path needs and is a strict prerequisite for that
  *                      larger feature, not a detour around it.
  */
class BorgTileFlusher(val dataBits: Int = 16, val samples: Int = 1,
                      val hasDepthFlush: Boolean = false,
                      val hasAlpha: Boolean = false,
                      val hasStencil: Boolean = false,
                      val zBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits, samples, hasDepthFlush, hasAlpha, hasStencil, zBits))

  val sIdle :: sFill :: sBurst :: sBurstZ :: sBurstS :: Nil = Enum(5)
  val state = RegInit(sIdle)

  // 16 staged halfwords (256 FFs): 16 RGB565 pixels, or 8 RGBA8 pixels as
  // low/high halfword pairs.
  val rgbVec   = Reg(Vec(16, UInt(16.W)))
  // 16 UNORM16 depth values, staged from the SAME sFill read pass as rgbVec
  // (another 256 FFs, only when hasDepthFlush).
  // Staged raw (the tile's own depth width) and converted to the attachment
  // format per burst beat: one converter instead of one per captured entry.
  val zVec     = if (hasDepthFlush) Some(Reg(Vec(16, UInt(zBits.W)))) else None
  val d32Reg   = RegInit(false.B)      // D32_SFLOAT, latched at start
  val zHalf    = RegInit(false.B)      // D32: which 8-entry half is bursting
  val baseReg  = RegInit(0.U(25.W))
  // Latched alongside baseReg for the same reason: the sequencer's address
  // inputs are only valid at the start pulse, not for the whole flush.
  val depthBaseReg = if (hasDepthFlush) Some(RegInit(0.U(25.W))) else None
  // 16 stencil bytes, staged in the same fill pass (only with hasStencil).
  val sVec           = if (hasStencil) Some(Reg(Vec(16, UInt(8.W)))) else None
  val stencilBaseReg = if (hasStencil) Some(RegInit(0.U(25.W))) else None
  val formatReg = RegInit(FlushFormat.RGB565.U(2.W))
  val wide      = FlushFormat.isWide(formatReg)
  // Wide formats only: which 8-pixel half of the tile is being staged/burst.
  val half      = RegInit(false.B)
  val issueIdx = RegInit(0.U(5.W))  // next entry to issue a read for (0..16)
  val capIdx   = RegInit(0.U(5.W))  // next entry to capture into rgbVec (0..16)
  val burstIdx = RegInit(0.U(5.W))  // entry currently being streamed (0..15)

  // Registered read-port outputs: set one cycle early so arcilator reads them
  // from the state array (always up-to-date), avoiding comb ordering issues.
  val readEnReg  = RegInit(false.B)
  val readIdxReg = RegInit(0.U(4.W))

  // Default outputs
  io.busy := (state =/= sIdle) || io.start

  io.read.en  := readEnReg
  io.read.idx := readIdxReg

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

  // Auto-clear the read-enable register each cycle; overridden below when needed.
  readEnReg := false.B

  // FP16 [0,1] -> unsigned N-bit channel (top N bits of an 8-bit conversion).
  // FP16: 1 sign + 5 exponent (bias=15) + 10 mantissa.  Clamp negatives to 0,
  // >=1.0 to all-ones; matches the scanout's old fp16ToRgb8 mapping.
  //
  // This is an 8-arm MuxLookup, i.e. real hardware (~140 cells measured via
  // synthesis), not a cheap function -- which is why the MSAA resolve below
  // goes out of its way to instantiate ONE of these per channel and time-share
  // it across samples rather than one per (channel, sample) pair. The first
  // cut of resolve called this `samples` times per channel combinationally
  // (see git history) and cost +1,312 cells in BorgTileFlusher alone at
  // samples=4 -- confirmed via yosys `stat` on the emitted ASIC netlist,
  // comparing samples=1 vs samples=4 module-by-module.
  def fp16ToUnorm(fp16: UInt, bits: Int): UInt = {
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val full = Cat(1.U(1.W), mant)  // 11-bit: 1.mantissa
    val rgb8 = Wire(UInt(8.W))
    when(sign || exp < 7.U) {
      rgb8 := 0.U
    }.elsewhen(exp >= 15.U) {
      rgb8 := 255.U
    }.otherwise {
      rgb8 := MuxLookup(exp, 0.U(8.W))(Seq(
        14.U -> full(10, 3),
        13.U -> Cat(0.U(1.W), full(10, 4)),
        12.U -> Cat(0.U(2.W), full(10, 5)),
        11.U -> Cat(0.U(3.W), full(10, 6)),
        10.U -> Cat(0.U(4.W), full(10, 7)),
         9.U -> Cat(0.U(5.W), full(10, 8)),
         8.U -> Cat(0.U(6.W), full(10, 9)),
         7.U -> Cat(0.U(7.W), full(10))
      ))
    }
    rgb8(7, 8 - bits)
  }

  /** Destination alpha of sample `s`, or opaque without an alpha plane. */
  def alphaOf(s: Int): UInt = io.alpha.map(_(s)).getOrElse(255.U(8.W))

  /** Stage one finished pixel, given as UNORM8 channels, for tile entry `idx`.
    *
    * RGB565 keeps the top bits of each channel -- exactly what converting
    * straight to 5/6/5 bits produced before the wide formats existed. The
    * 32-bit formats occupy two consecutive halfword slots, little-endian, so
    * the byte order in memory is Vulkan's: R8G8B8A8 is R,G,B,A from byte 0,
    * B8G8R8A8 is B,G,R,A. Only the low 3 bits of `idx` pick the slot pair,
    * because a wide tile is staged 8 pixels at a time. */
  def stagePixel(idx: UInt, r8: UInt, g8: UInt, b8: UInt, a8: UInt): Unit = {
    when(wide) {
      val bgra = formatReg === FlushFormat.BGRA8.U
      val slot = Cat(idx(2, 0), 0.U(1.W))
      rgbVec(slot)        := Cat(g8, Mux(bgra, b8, r8))   // bytes 1:0
      rgbVec(slot | 1.U)  := Cat(a8, Mux(bgra, r8, b8))   // bytes 3:2
    }.otherwise {
      rgbVec(idx) := Cat(r8(7, 3), g8(7, 2), b8(7, 3))
    }
  }

  // Fill-pipeline valid tracking: a read issued this cycle yields data 3 cycles
  // later.  issueValid marks the issue; v3 marks the matching data-valid cycle.
  val issueValid = WireDefault(false.B)
  val v1 = RegNext(issueValid, false.B)
  val v2 = RegNext(v1, false.B)
  val v3 = RegNext(v2, false.B)

  // Whether sFill may issue its next read this cycle. The single-sample path
  // issues one per cycle; the MSAA resolve below must drain first.
  val issueGate = WireDefault(true.B)

  // Stencil rides the fill like depth: the read that returns an entry's
  // colour returns its stencil too. capIdx still names that entry at v3 on
  // both paths (the MSAA path advances it only when the resolve retires).
  sVec.foreach(v => when(v3) { v(capIdx(3, 0)) := io.stencil.get(0) })

  /** After the depth burst (or the colour burst, without depth). */
  def afterDepth: UInt =
    if (hasStencil) Mux(io.stencilEn.get, sBurstS, sIdle) else sIdle

  if (samples == 1) {
    // Single-sample path: one fp16ToUnorm per channel, one cycle, fully
    // pipelined issuance. Kept as the regression anchor for non-MSAA builds
    // (every shipped config is MSAA now; the samples==1 tests keep it honest).
    when(v3) {
      val e = io.read.data(0)
      stagePixel(capIdx(3, 0), fp16ToUnorm(e.r, 8), fp16ToUnorm(e.g, 8),
                 fp16ToUnorm(e.b, 8), alphaOf(0))
      // Depth rides the same capture: io.read.data(0) is already valid here
      // for the colour conversion, and .z is simply another field of it, so
      // this adds a quantizer and a register write -- no extra read, no
      // extra cycle, no change to the colour path's timing.
      //
      // `data` is indexed by SAMPLE, so at samples>1 taking (0) here IS the
      // depth resolve: VK_RESOLVE_MODE_SAMPLE_ZERO_BIT, the only depth
      // resolve mode v3dv supports and the one V3D's tile-store hardware
      // selects (decimate_mode = SAMPLE_0). See the class doc comment.
      zVec.foreach(_(capIdx(3, 0)) := e.z)
      capIdx := capIdx + 1.U
    }
  } else {
    // MSAA resolve, SERIALIZED across samples: one shared fp16ToUnorm per
    // channel (3 total, not samples*3), fed one sample per cycle. Averaging
    // is done in the same UNORM INTEGER domain as before (sum of `samples`
    // 8-bit conversions, shifted right by log2(samples)) -- only the timing
    // changed, not the arithmetic or the result. Alpha is already UNORM8 in
    // the tile buffer, so it is averaged the same way with no converter.
    //
    // Correctness requires the read pipeline to fully drain (issue -> v3 ->
    // samples-cycle resolve -> capIdx write) before the NEXT read is issued:
    // the tile buffer's read port can have at most one outstanding request's
    // result live at a time, and resolving now takes longer than the 3-cycle
    // issue-to-v3 latency, so without this the pipeline would overtake itself
    // and read N+1's data would land on top of read N's before it finished
    // resolving. `pipelineBusy` enforces that: set at issue, held through the
    // entire resolve, cleared only when the resolved pixel is written to
    // rgbVec. This trades the original pipelined ~19-cycle fill phase for a
    // fully sequential ~16*(3+samples) cycles -- a real but small cost
    // against a frame's DRAM burst time, in exchange for not needing a
    // multi-pixel queue (which would have eaten back much of the area this
    // is meant to save).
    val sampleBits = log2Ceil(samples)
    val accBits    = 8 + sampleBits          // max sum = samples*255, fits exactly

    val pipelineBusy   = RegInit(false.B)
    val resolveBusy    = RegInit(false.B)
    val resolveSample  = RegInit(0.U(sampleBits.W))
    val pendingSamples = Reg(Vec(samples, new ColorZ(dataBits, zBits)))
    val pendingAlpha   = io.alpha.map(_ => Reg(Vec(samples, UInt(8.W))))
    val accR = RegInit(0.U(accBits.W))
    val accG = RegInit(0.U(accBits.W))
    val accB = RegInit(0.U(accBits.W))
    val accA = io.alpha.map(_ => RegInit(0.U(accBits.W)))

    issueGate := !pipelineBusy
    when(issueValid) { pipelineBusy := true.B }
    when(state === sIdle && io.start) {
      pipelineBusy := false.B
      resolveBusy  := false.B
    }

    when(v3) {
      pendingSamples := io.read.data
      pendingAlpha.foreach(_ := io.alpha.get)
      resolveBusy    := true.B
      resolveSample  := 0.U
      accR := 0.U
      accG := 0.U
      accB := 0.U
      accA.foreach(_ := 0.U)
    }

    when(resolveBusy) {
      val s     = pendingSamples(resolveSample)
      val rSum  = accR + fp16ToUnorm(s.r, 8)
      val gSum  = accG + fp16ToUnorm(s.g, 8)
      val bSum  = accB + fp16ToUnorm(s.b, 8)
      val aSum  = accA.map(a => a + pendingAlpha.get(resolveSample))
      when(resolveSample === (samples - 1).U) {
        stagePixel(capIdx(3, 0),
          (rSum >> sampleBits)(7, 0),
          (gSum >> sampleBits)(7, 0),
          (bSum >> sampleBits)(7, 0),
          aSum.map(a => (a >> sampleBits)(7, 0)).getOrElse(255.U(8.W)))
        // Depth resolves by taking SAMPLE ZERO, not this average: averaging
        // depth is meaningless across a triangle edge, and sample-zero is
        // the resolve mode Vulkan requires (VK_RESOLVE_MODE_SAMPLE_ZERO_BIT)
        // and the only one v3dv offers for depth. pendingSamples still holds
        // every captured sample here, so (0) is sample zero directly -- the
        // colour accumulators are untouched by this.
        zVec.foreach(_(capIdx(3, 0)) := pendingSamples(0).z)
        if (BorgDebug.trace) printf("[FLUSH] entry=%d resolved from %d samples\n",
          capIdx, samples.U)
        capIdx       := capIdx + 1.U
        resolveBusy  := false.B
        pipelineBusy := false.B
      }.otherwise {
        accR := rSum
        accG := gSum
        accB := bSum
        accA.zip(aSum).foreach { case (a, sum) => a := sum }
        resolveSample := resolveSample + 1.U
      }
    }
  }

  // Entries staged per burst: the whole tile for RGB565, half of it for the
  // 32-bit formats (see the class doc).
  val fillEnd = Mux(wide && !half, 8.U, 16.U)

  switch(state) {
    is(sIdle) {
      when(io.start) {
        baseReg   := io.tileBase
        depthBaseReg.foreach(_ := io.depthBase.get)
        stencilBaseReg.foreach(_ := io.stencilBase.get)
        d32Reg    := io.depthD32.getOrElse(false.B)
        zHalf     := false.B
        formatReg := io.format
        half      := false.B
        issueIdx  := 0.U
        capIdx    := 0.U
        burstIdx  := 0.U
        state     := sFill
      }
    }
    // Issue reads for the entries of this burst; captures land via v3 above.
    // Advance to the burst once they are all captured.
    is(sFill) {
      when(issueIdx < fillEnd && issueGate) {
        readEnReg  := true.B
        readIdxReg := issueIdx(3, 0)
        issueValid := true.B
        issueIdx   := issueIdx + 1.U
      }
      when(capIdx === fillEnd) {
        state := sBurst
      }
    }
    is(sBurst) {
      io.gpuMem.wr    := true.B
      // The second half of a wide tile follows the first 32 bytes.
      io.gpuMem.addr  := baseReg + Mux(half, 32.U, 0.U)
      io.gpuMem.wdata := rgbVec(burstIdx(3, 0))
      io.gpuMem.wlen  := 16.U
      when(io.gpuMem.waccept) {
        if (BorgDebug.trace) printf("[FLUSH] beat=%d colour=0x%x\n",
          burstIdx, rgbVec(burstIdx(3, 0)))
        burstIdx := burstIdx + 1.U
      }
      when(io.gpuMem.ready) {
        burstIdx := 0.U
        when(wide && !half) {
          // Back to sFill for pixels 8..15; issueIdx/capIdx carry on from 8.
          half  := true.B
          state := sFill
        }.otherwise {
          // With depth disabled (or not built at all) this is the historical
          // sBurst -> sIdle edge, unchanged.
          if (hasDepthFlush) {
            state := Mux(io.depthEn.get, sBurstZ, afterDepth)
          } else {
            state := afterDepth
          }
        }
      }
    }
    // Second burst: the tile's Z plane as 16 UNORM16 words, to the
    // depth-buffer region. Structurally identical to the colour burst
    // above -- same 16-beat wlen, same waccept/ready handshake -- just a
    // different staging vector and base address. zVec was staged from
    // sample zero as each pixel was captured.
    //
    // The `is()` arm itself is unconditional because Chisel's switch macro
    // rejects any block that doesn't begin with is(); only the BODY varies
    // at elaboration. In a hasDepthFlush=false build nothing ever
    // transitions into sBurstZ (sBurst exits straight to sIdle above), so
    // this arm is unreachable and folds away in synthesis.
    is(sBurstZ) {
      if (hasDepthFlush) {
        // D16_UNORM: one converted value per beat. D32_SFLOAT: the raw FP32,
        // low halfword first, 8 entries per burst.
        val z = zVec.get
        val d16 = if (zBits == 32) DepthQuantize.quantize16Fp32(z(burstIdx(3, 0)))
                  else DepthQuantize.quantize16(z(burstIdx(3, 0)))
        val d32Entry = z(Cat(zHalf, burstIdx(3, 1)))
        val d32Half  = if (zBits == 32) Mux(burstIdx(0), d32Entry(31, 16), d32Entry(15, 0)) else 0.U
        io.gpuMem.wr    := true.B
        io.gpuMem.addr  := depthBaseReg.get + Mux(zHalf, 32.U, 0.U)
        io.gpuMem.wdata := Mux(d32Reg, d32Half, d16)
        io.gpuMem.wlen  := 16.U
        when(io.gpuMem.waccept) {
          if (BorgDebug.trace) printf("[FLUSH] depth beat=%d data=0x%x\n",
            burstIdx, io.gpuMem.wdata)
          burstIdx := burstIdx + 1.U
        }
        when(io.gpuMem.ready) {
          burstIdx := 0.U
          when(d32Reg && !zHalf) {
            zHalf := true.B                 // second 32 bytes of a D32 tile
          }.otherwise {
            state := afterDepth
          }
        }
      } else {
        state := sIdle
      }
    }
    // Third burst: the stencil plane, two S8 values per halfword beat.
    is(sBurstS) {
      if (hasStencil) {
        io.gpuMem.wr    := true.B
        io.gpuMem.addr  := stencilBaseReg.get
        io.gpuMem.wdata := Cat(sVec.get(Cat(burstIdx(2, 0), 1.U(1.W))),
                               sVec.get(Cat(burstIdx(2, 0), 0.U(1.W))))
        io.gpuMem.wlen  := 8.U
        when(io.gpuMem.waccept) { burstIdx := burstIdx + 1.U }
        when(io.gpuMem.ready) { state := sIdle }
      } else {
        state := sIdle
      }
    }
  }
}
