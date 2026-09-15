// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** IO bundle for [[BorgTileFlusher]].
  *
  * Directions are from the flusher's perspective (master).
  */
class BorgTileFlusherIO(val dataBits: Int = 16, val samples: Int = 1,
                        val hasDepthFlush: Boolean = false) extends Bundle {
  // Trigger interface
  val start     = Input(Bool())    // one-cycle pulse to begin flush
  val busy      = Output(Bool())   // high while flushing

  // Tile SRAM read port (flusher drives idx/en, reads data)
  val read      = new TileReadIO(dataBits, samples)

  // DRAM write port
  val gpuMem    = new GpuMemIO

  // Tile base address: absolute DRAM byte address of this tile's region.
  // Layout (RGB565): 16 entries × 2 bytes = 32 bytes per tile.
  //   word[i] = RGB565(entry[i])   (R[15:11] | G[10:5] | B[4:0])
  // Firmware computes: tileBase = fbBase + tile_index * 32
  //   where tile_index = (ty >> 2) * tiles_per_row + (tx >> 2)
  val tileBase  = Input(UInt(25.W))

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
  * Streams all 16 tile-buffer entries to SDRAM as ONE 16-word RGB565 burst.
  * Each pixel becomes a single 16-bit word (R5|G6|B5); depth is dropped (the
  * TBR renders each tile fully on-chip, so DRAM never needs the Z value).
  * This halves the flush bandwidth vs the previous 64-word FP16 R/G/B/Z burst.
  *
  * Two phases:
  *   sFill  -- read all 16 tile entries (pipelined), convert FP16->RGB565,
  *             stash into rgbVec.  The 2-cycle TileBuffer read latency is hidden
  *             by issuing one read per cycle and capturing 3 cycles later.
  *   sBurst -- stream the 16 RGB565 words from rgbVec as one burst.  rgbVec is
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
  *                      below. (Precisely: the sBurstZ enum value and its
  *                      switch arm ARE still elaborated, because Chisel's
  *                      switch macro won't accept a conditionally-present
  *                      is() arm -- but nothing transitions into it, so it
  *                      is unreachable and folds away in synthesis, and the
  *                      state register width is unchanged since
  *                      log2Ceil(3) == log2Ceil(4).)
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
                      val hasDepthFlush: Boolean = false) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits, samples, hasDepthFlush))

  val sIdle :: sFill :: sBurst :: sBurstZ :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // 16 RGB565 pixels staged before the burst (256 FFs).
  val rgbVec   = Reg(Vec(16, UInt(16.W)))
  // 16 UNORM16 depth values, staged from the SAME sFill read pass as rgbVec
  // (another 256 FFs, only when hasDepthFlush).
  val zVec     = if (hasDepthFlush) Some(Reg(Vec(16, UInt(16.W)))) else None
  val baseReg  = RegInit(0.U(25.W))
  // Latched alongside baseReg for the same reason: the sequencer's address
  // inputs are only valid at the start pulse, not for the whole flush.
  val depthBaseReg = if (hasDepthFlush) Some(RegInit(0.U(25.W))) else None
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

  // Fill-pipeline valid tracking: a read issued this cycle yields data 3 cycles
  // later.  issueValid marks the issue; v3 marks the matching data-valid cycle.
  val issueValid = WireDefault(false.B)
  val v1 = RegNext(issueValid, false.B)
  val v2 = RegNext(v1, false.B)
  val v3 = RegNext(v2, false.B)

  if (samples == 1) {
    // Untouched single-sample path -- the bit-identical AND cycle-identical
    // regression anchor for every non-MSAA target (including BorgConfig.Asic
    // until the MSAA config is actually selected). One fp16ToUnorm per
    // channel, one cycle, fully pipelined issuance exactly as before this
    // file gained a `samples` parameter.
    def toRgb565(entry: Vec[ColorZ]): UInt = {
      val r5 = fp16ToUnorm(entry(0).r, 5)
      val g6 = fp16ToUnorm(entry(0).g, 6)
      val b5 = fp16ToUnorm(entry(0).b, 5)
      Cat(r5, g6, b5)
    }

    when(v3) {
      rgbVec(capIdx(3, 0)) := toRgb565(io.read.data)
      // Depth rides the same capture: io.read.data(0) is already valid here
      // for the colour conversion, and .z is simply another field of it, so
      // this adds a quantizer and a register write -- no extra read, no
      // extra cycle, no change to the colour path's timing.
      //
      // `data` is indexed by SAMPLE, so at samples>1 taking (0) here IS the
      // depth resolve: VK_RESOLVE_MODE_SAMPLE_ZERO_BIT, the only depth
      // resolve mode v3dv supports and the one V3D's tile-store hardware
      // selects (decimate_mode = SAMPLE_0). See the class doc comment.
      zVec.foreach(_(capIdx(3, 0)) := DepthQuantize.quantize16(io.read.data(0).z))
      capIdx := capIdx + 1.U
    }

    switch(state) {
      is(sIdle) {
        when(io.start) {
          baseReg  := io.tileBase
          depthBaseReg.foreach(_ := io.depthBase.get)
          issueIdx := 0.U
          capIdx   := 0.U
          burstIdx := 0.U
          state    := sFill
        }
      }
      // Issue one read per cycle for entries 0..15; captures land via v3 above.
      // Advance to the burst once all 16 entries are captured.
      is(sFill) {
        when(issueIdx < 16.U) {
          readEnReg  := true.B
          readIdxReg := issueIdx(3, 0)
          issueValid := true.B
          issueIdx   := issueIdx + 1.U
        }
        when(capIdx === 16.U) {
          state := sBurst
        }
      }
      is(sBurst) {
        io.gpuMem.wr    := true.B
        io.gpuMem.addr  := baseReg
        io.gpuMem.wdata := rgbVec(burstIdx(3, 0))
        io.gpuMem.wlen  := 16.U
        when(io.gpuMem.waccept) {
          if (BorgDebug.trace) printf("[FLUSH] entry=%d RGB565=0x%x\n",
            burstIdx, rgbVec(burstIdx(3, 0)))
          burstIdx := burstIdx + 1.U
        }
        when(io.gpuMem.ready) {
          // With depth disabled (or not built at all) this is the historical
          // sBurst -> sIdle edge, unchanged.
          if (hasDepthFlush) {
            when(io.depthEn.get) {
              burstIdx := 0.U
              state    := sBurstZ
            }.otherwise {
              state := sIdle
            }
          } else {
            state := sIdle
          }
        }
      }
      // Second burst: the tile's Z plane as 16 UNORM16 words, to the
      // depth-buffer region. Structurally identical to the colour burst
      // above -- same 16-beat wlen, same waccept/ready handshake -- just a
      // different staging vector and base address.
      //
      // The `is()` arm itself is unconditional because Chisel's switch macro
      // rejects any block that doesn't begin with is(); only the BODY varies
      // at elaboration. In a hasDepthFlush=false build nothing ever
      // transitions into sBurstZ (sBurst exits straight to sIdle above), so
      // this arm is unreachable and folds away in synthesis, and the state
      // register stays 2 bits wide either way (log2Ceil(3) == log2Ceil(4)).
      is(sBurstZ) {
        if (hasDepthFlush) {
          io.gpuMem.wr    := true.B
          io.gpuMem.addr  := depthBaseReg.get
          io.gpuMem.wdata := zVec.get(burstIdx(3, 0))
          io.gpuMem.wlen  := 16.U
          when(io.gpuMem.waccept) {
            if (BorgDebug.trace) printf("[FLUSH] entry=%d D16=0x%x\n",
              burstIdx, zVec.get(burstIdx(3, 0)))
            burstIdx := burstIdx + 1.U
          }
          when(io.gpuMem.ready) {
            state := sIdle
          }
        } else {
          state := sIdle
        }
      }
    }
  } else {
    // MSAA resolve, SERIALIZED across samples: one shared fp16ToUnorm per
    // channel (3 total, not samples*3), fed one sample per cycle. Averaging
    // is done in the same UNORM INTEGER domain as before (sum of `samples`
    // 8-bit conversions, shifted right by log2(samples)) -- only the timing
    // changed, not the arithmetic or the result.
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
    val pendingSamples = Reg(Vec(samples, new ColorZ(dataBits)))
    val accR = RegInit(0.U(accBits.W))
    val accG = RegInit(0.U(accBits.W))
    val accB = RegInit(0.U(accBits.W))

    when(v3) {
      pendingSamples := io.read.data
      resolveBusy    := true.B
      resolveSample  := 0.U
      accR := 0.U
      accG := 0.U
      accB := 0.U
    }

    when(resolveBusy) {
      val s     = pendingSamples(resolveSample)
      val rTerm = fp16ToUnorm(s.r, 8)
      val gTerm = fp16ToUnorm(s.g, 8)
      val bTerm = fp16ToUnorm(s.b, 8)
      val rSum  = accR + rTerm
      val gSum  = accG + gTerm
      val bSum  = accB + bTerm
      when(resolveSample === (samples - 1).U) {
        rgbVec(capIdx(3, 0)) := Cat(
          (rSum >> sampleBits)(7, 3),
          (gSum >> sampleBits)(7, 2),
          (bSum >> sampleBits)(7, 3))
        // Depth resolves by taking SAMPLE ZERO, not this average: averaging
        // depth is meaningless across a triangle edge, and sample-zero is
        // the resolve mode Vulkan requires (VK_RESOLVE_MODE_SAMPLE_ZERO_BIT)
        // and the only one v3dv offers for depth. pendingSamples still holds
        // every captured sample here, so (0) is sample zero directly -- the
        // colour accumulators are untouched by this.
        zVec.foreach(_(capIdx(3, 0)) :=
          DepthQuantize.quantize16(pendingSamples(0).z))
        if (BorgDebug.trace) printf("[FLUSH] entry=%d resolved RGB565 from %d samples\n",
          capIdx, samples.U)
        capIdx       := capIdx + 1.U
        resolveBusy  := false.B
        pipelineBusy := false.B
      }.otherwise {
        accR := rSum
        accG := gSum
        accB := bSum
        resolveSample := resolveSample + 1.U
      }
    }

    switch(state) {
      is(sIdle) {
        when(io.start) {
          baseReg      := io.tileBase
          // Latched at the start pulse for the same reason as baseReg: the
          // sequencer's address inputs are only valid then, not for the
          // whole flush.
          depthBaseReg.foreach(_ := io.depthBase.get)
          issueIdx     := 0.U
          capIdx       := 0.U
          burstIdx     := 0.U
          pipelineBusy := false.B
          resolveBusy  := false.B
          state        := sFill
        }
      }
      is(sFill) {
        when(issueIdx < 16.U && !pipelineBusy) {
          readEnReg    := true.B
          readIdxReg   := issueIdx(3, 0)
          issueValid   := true.B
          issueIdx     := issueIdx + 1.U
          pipelineBusy := true.B
        }
        when(capIdx === 16.U) {
          state := sBurst
        }
      }
      is(sBurst) {
        io.gpuMem.wr    := true.B
        io.gpuMem.addr  := baseReg
        io.gpuMem.wdata := rgbVec(burstIdx(3, 0))
        io.gpuMem.wlen  := 16.U
        when(io.gpuMem.waccept) {
          if (BorgDebug.trace) printf("[FLUSH] entry=%d RGB565=0x%x\n",
            burstIdx, rgbVec(burstIdx(3, 0)))
          burstIdx := burstIdx + 1.U
        }
        when(io.gpuMem.ready) {
          // Same depth hand-off as the single-sample branch above: with a
          // depth attachment bound, the colour burst is followed by the Z
          // burst; without one, this is the historical sBurst -> sIdle edge.
          if (hasDepthFlush) {
            when(io.depthEn.get) {
              burstIdx := 0.U
              state    := sBurstZ
            }.otherwise {
              state := sIdle
            }
          } else {
            state := sIdle
          }
        }
      }
      // The Z burst, identical in shape to the single-sample branch's: 16
      // UNORM16 words to depthBaseReg. zVec was staged from sample zero as
      // each pixel's colour resolve completed.
      is(sBurstZ) {
        if (hasDepthFlush) {
          io.gpuMem.wr    := true.B
          io.gpuMem.addr  := depthBaseReg.get
          io.gpuMem.wdata := zVec.get(burstIdx(3, 0))
          io.gpuMem.wlen  := 16.U
          when(io.gpuMem.waccept) {
            if (BorgDebug.trace) printf("[FLUSH] entry=%d D16=0x%x\n",
              burstIdx, zVec.get(burstIdx(3, 0)))
            burstIdx := burstIdx + 1.U
          }
          when(io.gpuMem.ready) {
            state := sIdle
          }
        } else {
          state := sIdle
        }
      }
    }
  }
}
