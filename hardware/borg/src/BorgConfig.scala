// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Build-time configuration for the Borg GPU.
  *
  * Encapsulates all target-specific knobs so that a single parameter
  * flows through the entire hierarchy.
  *
  * @param fp           Floating-point format (FP16 for iCE40, FP32 for future ASIC)
  * @param coordWidth   Pixel coordinate width: max framebuffer dimension = 2^coordWidth.
  *                     6 → 64px (FPGA), 9 → 512px (ASIC/sim for 500×500 renders)
  * @param fifoDepth    Command FIFO depth.
  * @param maxBinTiles     Maximum number of tiles tracked by BorgBinner's on-chip
  *                        count SRAM.  Each entry costs 10 bits of flip-flops:
  *                        1024 entries ≈ 920 kµm² (50 % of the IHP 8×4 tile!).
  *                        ULX3S/sim uses 1024 (covers 800×480 @ 4×4 tiles = 24000 →
  *                        capped; real TBR over 1024 tiles only).
  *                        ASIC uses 16 to fit the IHP 8×4 die (16-tile render grid).
  * @param maxInstructions Shader instruction memory depth.  Each entry is 32 bits.
  *                        56 entries ≈ 145 kµm²; 32 entries ≈ 83 kµm² (saves 62 kµm²).
  *                        The rasterizer edge-test shader is a separate, permanent
  *                        ROM (BorgRasterRom) and does not consume this budget --
  *                        this is purely the fragment-shader (and, time-multiplexed,
  *                        vertex-shader) writable IMEM. ASIC uses 64, sized to fit
  *                        cube.frag's 59 words.
  * @param maxUniforms  Uniform memory depth.  64 = two 32-entry pages (double-buffered
  *                     for CPU/GPU overlap); 32 = single page (saves ~25 kµm² on ASIC
  *                     where the sequencer always writes page 0).
  * @param hasPerfCounters Wire up the 5×32-bit GPU performance counters (total/frag/
  *                     flush/stall/dma).  Useful for fps profiling on ULX3S; omitted
  *                     on ASIC to save ~18 kµm².
  * @param fragLanes  Fragment-shader SIMT width.  1 = scalar (one pixel per shader pass,
  *                     the current behaviour); 4 = a 2×2 pixel quad per pass (4× shader
  *                     throughput, lays the architecture for dFdx/dFdy).  ULX3S/sim use 4;
  *                     ASIC stays at 1 (area).
  * @param samples  Multisample (MSAA) rate: colour+depth samples stored per pixel in
  *                 the tile buffer.  1 = single-sample (historical behaviour, the
  *                 bit-exact regression anchor); 4 = 4× MSAA, required by Vulkan's
  *                 `framebufferColorSampleCounts` minimum limit.  One fragment shade
  *                 per pixel is broadcast to every covered sample (standard MSAA, NOT
  *                 `sampleRateShading`, which Vulkan permits reporting unsupported).
  *                 Costs `(samples-1) * 64` bits per tile entry; the resolve (average)
  *                 happens in BorgTileFlusher so the DRAM burst format is unchanged.
  * @param maxTrianglesPerTile Upper bound on BorgSequencer/BorgBinner's `binRowBytes`
  *                     MMIO input (= this * 2 bytes/entry), used to narrow the
  *                     tile-index*binRowBytes multiplier's width instead of leaving
  *                     it at the register's full 20 bits. MUST match (or exceed)
  *                     software/borg/borg_layout.h's SEQ_MAX_TRI -- that's a single
  *                     compile-time constant shared unconditionally by every target
  *                     (borg_driver.c's only write site: `seq_bin_row_bytes =
  *                     TBR_BIN_ROW_BYTES = SEQ_MAX_TRI*2`), so 256 here is safe for
  *                     both Default/Simt and Asic without any firmware coordination.
  *                     If SEQ_MAX_TRI ever grows, this must grow with it.
  */
case class BorgConfig(
    fp: FloatConfig = FloatConfig.FP16,
    coordWidth: Int = 9,
    fifoDepth: Int = 2,
    maxBinTiles: Int = 1024,
    maxInstructions: Int = 56,
    icacheLines: Int = 512,
    maxUniforms: Int = 64,
    hasPerfCounters: Boolean = true,
    fragLanes: Int = 1,
    maxTrianglesPerTile: Int = 256,
    samples: Int = 1,
    // Gates Borg.scala's covDeltaDebug diagnostic port (only elaborated at
    // all when samples>1 to begin with). True everywhere except
    // BorgConfig.Wafer, since the wafer.space Borg-only bridge target has no
    // debug harness to observe it, unlike ULX3S/sim. Unrelated to BorgIO's
    // uo_out/user_interrupt, which are dead (tied to constants) for every
    // config and are simply deleted outright, not gated by this flag.
    debugPorts: Boolean = true,
    // BorgTileBuffer's per-sample R/G/B storage width, independent of `fp`.
    // Default 16 keeps every existing target (including today's signed-off
    // wafer.space GDS) bit-identical -- this narrows ONLY the internal SRAM
    // of the tile buffer via ColorQuantize; TileWriteIO/TileReadIO stay FP16
    // at the port on every config, so nothing outside BorgTileBuffer changes
    // shape. Z is deliberately excluded: a color quantized to a broadcast
    // shading value compresses cleanly, but Z varies continuously per-sample
    // even within one triangle on a sloped surface, so there is no similar
    // "the final format doesn't need this precision" argument for it. Only
    // 16 (off) and 8 (ColorQuantize's UNORM8 path) exist; 8 costs one exact
    // sub-half-LSB tie in 256 (see ColorQuantizeTests' round-trip test) in
    // exchange for roughly 37% less tile-buffer storage.
    tileColorBits: Int = 16,
    // BorgFp16Fma pipeline depth. 3 is the shipping FP16 form; 4 and 5 add
    // registers inside stages 2 and 3 respectively, for FP32 at 25 MHz.
    //
    // THE CELL LIBRARY DOMINATES THIS DECISION -- check which one your target
    // builds against before concluding anything. Measured with OpenSTA at a
    // 40 ns (25 MHz) period, synthesis-only (no wire delay), Log2 msbPos:
    //
    //                   5V fd_sc_mcu7t5v0     3.3V as_sc_mcu7t3v3
    //   FP16 3-stage    31.256 (+8.453)       18.035 (+21.783)
    //   FP32 3-stage    55.687 (-15.968 VIOL) 26.690 (+13.131 MET)
    //   FP32 4-stage    38.884 (+0.679)       18.740 (+21.074)
    //   FP32 5-stage    42.047 (-2.334 VIOL)  22.446 (+17.375)
    //
    // The 3.3V cells are ~1.7-2.1x faster (and ~7% larger). On 3.3V, FP32
    // closes 25 MHz UNSPLIT with 33% margin -- fmaStages>3 buys nothing.
    // Only on the 5V library (what the 4 MHz config ships) does FP32 need
    // splitting, and even then 4 stages is marginal. librelane/
    // probe_borgonly_3v3.yaml is the 3.3V/25 MHz precedent.
    //
    // FP32 blows stage 2 up because F grows 40 -> 66 bits and the stage chains
    // a 66-bit barrel shift, a two's-complement negate, a 68-bit add and a
    // second negate. Splitting after alignment (stages=4) separates the shift
    // work from the three carry chains and moves the path into stage 3.
    //
    // stages=5 splits stage 3 after dropAmt, and measures WORSE than 4 on both
    // libraries: the path moves to stage 2b, starting at x_prodSign with a
    // 3.734 ns first-gate delay -- one sign bit driving a 67-bit conditional
    // negate (Mux(sign, -(x.zext), x.zext)), whose fanout ABC buffers poorly
    // without placement. Fixing that means restructuring the add as
    // "same signs -> add, differing -> subtract" instead of two conditional
    // negates: a numerics-sensitive rewrite of a module verified bit-identical
    // to HardFloat over 30k vectors. Not worth doing unless a 5V 25 MHz build
    // is actually required.
    //
    // Caution: 2-4 ns differences here are within synthesis-to-synthesis
    // variance (ABC's buffering/sizing shifts without placement data). The
    // library gap and the 3-vs-4 gap are real; 4-vs-5 is indicative only.
    // Settle any 25 MHz claim with a real LibreLane run, not pre-layout STA.
    //
    // Context: the wafer.space flow ships at 4 MHz today
    // (librelane/config.yaml CLOCK_PERIOD 250), where even the unsplit FP32
    // stage 2 has ~192 ns of slack. These splits exist for the 25 MHz target.
    // Synthesis-only numbers run optimistic: FP16 measures 30.9 MHz here but
    // signs off at 25, implying ~1.24x layout degradation -- so budget margin
    // rather than trusting a barely-passing synthesis slack.
    //
    // NOTE: raising this alone is NOT functionally correct -- each extra stage
    // adds a pipeline cycle, so BorgCore's busy_counter must widen from 3 bits
    // and load 7+N instead of 7, shifting holdA/B/C and pipeEn1 earlier.
    fmaStages: Int = 3
) {
  require(fragLanes == 1 || fragLanes == 4, s"fragLanes must be 1 or 4, got $fragLanes")
  require(samples == 1 || samples == 4, s"samples must be 1 or 4, got $samples")
  require(tileColorBits == 16 || tileColorBits == 8,
          s"tileColorBits must be 16 (off) or 8 (ColorQuantize UNORM8), got $tileColorBits")
  require(fmaStages >= 3 && fmaStages <= 5, s"fmaStages must be 3, 4 or 5, got $fmaStages")
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig
}

object BorgConfig {
  // Default: sim + ULX3S — full 1024-tile bin table, 56-instruction shader memory.
  // The in-tree BorgFp16Fma (CERN-OHL-S, round-to-nearest-even) is the sole FP16 FMA
  // across ALL targets — historically bit-verified vs IEEE/HardFloat (30k+ co-sim),
  // renders correctly in verilator/arcilator/ULX3S, smaller + shorter critical path.
  val Default = BorgConfig(
    fp              = FloatConfig.FP16,
    coordWidth      = 9,
    fifoDepth       = 2,
    maxBinTiles     = 1024,
    maxInstructions = 72 // M5 step 1: grow IMEM (rast 13 + frag ~56 co-resident)
  )

  // Sim + ULX3S SIMT config: 2×2 quad fragment shading.  Selected via BORG_CFG in
  // the sim tops and ULX3S; the scalar Default keeps the chisel unit tests on
  // the bit-exact single-lane reference.  maxBinTiles=1024 covers 128×128 @ 4×4
  // (32×32 = 1024 tiles), which is the current demo resolution.
  val Simt = Default.copy(fragLanes = 4, maxBinTiles = 1024)

  // ASIC (IHP SG13G2, TT 8×4 tile).
  //   countMem_1024x10 alone was ~920 kµm² (50 % of die) → reduced to 16 tiles (~14 kµm²).
  //   maxInstructions=64: the rasterizer edge-test shader (13 words) no longer lives
  //     in this writable IMEM at all -- it's baked into a permanent ROM (BorgRasterRom),
  //     fetched by BorgCore independently. This budget is now frag-only: cube.frag
  //     is 59 words, +1 word BORG_IMEM_FRAG_OFFSET (kept nonzero so fragPcReg==0 can
  //     still mean "no fragment shader"), +1 HALT sentinel = 61 of 64 used.
  //   icacheLines=0: I-cache bypassed — at 4 MHz QSPI latency is trivial; saves ~55 kµm².
  //   maxUniforms=32: single-page uniforms — sequencer always writes page 0; saves ~25 kµm².
  //   hasPerfCounters=false: 5×32-bit counters not needed for silicon demo; saves ~18 kµm².
  //   fragLanes=4 + samples=4: 4-lane SIMT and 4x MSAA both enabled. Verified by a
  //     full wafer.space 1x0.5 signoff -- 85.68 % utilisation, DRC/LVS/antenna clean,
  //     4.52 mW. The earlier 0.5x1 orientation failed detailed placement (DPL-0036) at
  //     81.97 %; 1x0.5 is the orientation that fits. Real Max Slew / Max Cap warnings
  //     remain outstanding -- electrical, not frequency-related, at 4 MHz.
  val Asic = BorgConfig(
    fp               = FloatConfig.FP16,
    coordWidth       = 7,
    fifoDepth        = 2,
    maxBinTiles      = 16,
    maxInstructions  = 64,
    icacheLines      = 0,
    maxUniforms      = 32,
    hasPerfCounters  = false,
    fragLanes        = 4,
    samples          = 4,
    // tileColorBits=8: BorgTileBuffer stores R/G/B as UNORM8 (via
    // ColorQuantize) instead of full FP16, quantizing on write and
    // dequantizing on read entirely internally -- TileWriteIO/TileReadIO
    // stay FP16 at the port, so nothing outside BorgTileBuffer changes. Z
    // stays FP16 (never quantized -- see BorgConfig.tileColorBits's own doc
    // for why). Measured: rgbzMems_16x64 -> rgbzMems_16x40, -37.2% per MSAA
    // sample plane, -8.75% (2,323,169 -> 2,119,850 um^2) on the whole
    // BorgOnlyCore hierarchy after the quantizer/dequantizer's own added
    // logic is accounted for. Verified: full hardware.borg.test (195/195)
    // at both tileColorBits=16 (unaffected) and =8 (new dedicated tests in
    // BorgTileBufferTests/ColorQuantizeTests), incl. the full render-pipeline
    // end-to-end tests and MSAA per-sample coverage masking against the
    // narrower storage.
    tileColorBits    = 8
  )

  // wafer.space Borg-only bridge target (BorgOnlyTop): same sizing as Asic
  // (proven by the Phase 0 probes -- see BorgOnlyTop.scala's doc), minus the
  // TT-pad-interface-only uo_out/user_interrupt ports and the covDeltaDebug
  // diagnostic tap, since BorgOnlyTop has no SoCLogic/CPU harness to expose
  // either through.
  val Wafer = Asic.copy(debugPorts = false)
}
