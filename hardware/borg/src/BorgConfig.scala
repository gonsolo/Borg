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
  *                     both Default/Simt and Wafer without any firmware coordination.
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
    // debug harness to observe it, unlike ULX3S/sim. (QspiSocTop, the cocotb
    // SoC harness, also builds from Wafer; nothing in SoCLogic reads the tap.)
    // Unrelated to BorgIO's
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
    // Adds BorgTileFlusher's optional second DRAM burst, writing the tile's
    // Z plane (FP16 -> UNORM16 via DepthQuantize) to the FLUSH_ZB_BASE
    // region -- the hardware half of `D16_UNORM` depth-attachment support
    // (Step 50 item 14). The parameter default is false only so the knob's
    // own tests can build a design without it; BorgConfig.Default turns it
    // on. Historically Z was never written to DRAM at all (the TBR keeps it
    // on-chip), which is why a mandatory Vulkan depth format had no path to
    // exist as a real image. Costs a
    // 16x16-bit staging vector plus a second burst pass per tile when
    // enabled; the runtime FLUSH_ZB_BASE!=0 gate means even an enabled
    // build behaves exactly like a disabled one until firmware actually
    // binds a depth buffer. At samples>1 the flush resolves depth by taking
    // sample 0 (VK_RESOLVE_MODE_SAMPLE_ZERO_BIT -- the only depth resolve
    // mode v3dv supports, and what V3D's tile-store hardware does); see
    // BorgTileFlusher's doc comment. A true multisampled depth ATTACHMENT
    // (all samples stored, 4x the memory) remains unsupported.
    hasDepthFlush: Boolean = false,
    // Adds the fixed-function colour blend stage ([[BorgBlend]]) to the
    // dispatcher's tile-write path -- Vulkan-conformance item 9. Blending is
    // core functionality, not an optional feature (only `independentBlend`
    // and `dualSrcBlend` are the optional extras), and Borg had none: every
    // tile write was an unconditional overwrite.
    //
    // Costs the blend equation itself (eight 8x8 multipliers plus the factor
    // muxes) and one 16-bit per-lane register for the fragment's alpha
    // output. The parameter default is false only so the knob's own tests
    // can build without it; BorgConfig.Default turns it on.
    //
    // Even in an enabled build the runtime `blend_cfg.enable` bit passes the
    // fragment's original FP16 colour straight through, so nothing pays the
    // FP16 -> UNORM8 -> FP16 round trip until an application actually turns
    // blending on.
    //
    // At samples>1 the destination colour is per-sample while TileWriteIO
    // carries one shared `data`, so BorgShaderDispatcher serializes the tile
    // write over samples (needPerSample / sampleCtr) instead of widening the
    // port; plain MSAA without blend/stencil keeps the single-cycle write.
    hasBlend: Boolean = false,
    // Adds the stencil plane and the fixed-function stencil test/update
    // ([[BorgStencil]]) -- Vulkan-conformance item 10. Stencil is mandatory;
    // no VkPhysicalDeviceFeatures bit gates it, and Borg had no stencil
    // concept at all.
    //
    // Costs one 16x8-bit SyncReadMem per sample in the tile buffer plus the
    // test/op logic. The parameter default is false only for the knob's own
    // tests; BorgConfig.Default turns it on. The runtime `stencil_cfg.enable`
    // bit keeps even an enabled build behaving exactly like a disabled one
    // until firmware turns it on.
    //
    // At samples>1 each sample's stencil update depends on its own stored
    // value, which is why the dispatcher's serialized per-sample tile write
    // (see hasBlend) exists.
    hasStencil: Boolean = false,
    // Adds bilinear texture filtering (VK_FILTER_LINEAR) to BorgTextureUnit.
    // Core Vulkan -- no feature bit gates linear filtering -- and Borg
    // sampled nearest-neighbour only.
    //
    // Costs three UNORM8 tap stores (96 bits), the weight arithmetic, and
    // 4x the DRAM reads per filtered sample: a texel is already two reads
    // because of the packed layout, so a filtered one is eight. There is no
    // coalescing yet even though the four taps of a 2x2 footprint are
    // adjacent in Morton order, which is the obvious later optimization.
    //
    // The parameter default is false only for the knob's own tests;
    // BorgConfig.Default turns it on. The runtime SAMPLER_CFG filter bit
    // keeps even an enabled build sampling nearest -- and paying no
    // quantize/dequantize round trip -- until an application asks for linear.
    hasBilinear: Boolean = false,
    // Number of simultaneously-bound textures FTEX can select between (its
    // rs3 operand -- see Instructions.FUNCT2_FTEX). Only the base DRAM
    // address is per-slot; dimension, address mode and filtering stay
    // shared/global, so every simultaneously-bound texture must be the same
    // size -- a real, deliberate scoping limit, not an oversight.
    //
    // 4 is conservative, not the Vulkan-declared minimum: the CTS's own
    // required-limits check (vktApiFeatureInfo.cpp) puts
    // maxPerStageDescriptorSampledImages/Samplers at 16. Raising this is a
    // bounded, mechanical change (widen the tex_base_addr RDL array and this
    // field) -- deferred to an explicit area-vs-declared-limit call, the
    // same shape as the hasCompute decision.
    maxTextureBindings: Int = 4,
    // --- MSAA storage strategy ------------------------------------------
    //
    // false (default): every sample of the tile is resident at once --
    // `samples` copies of the colour/Z, stencil and destination-alpha planes.
    // 4x MSAA costs 536,286 um2 on GF180 for 3,584 bits, because only 43% of
    // a 16-deep flop array is storage; the other 57% is its read mux and
    // write decode, paid twelve times over.
    //
    // true: render each tile ONCE PER SAMPLE, keeping a single plane of each
    // plus a per-pixel colour accumulator, and average at the end. Storage
    // drops to ~206,000 um2 -- 8.9% of the whole design -- for roughly 4x the
    // fragment work, because every pass re-shades the tile.
    //
    // This is still MSAA, not supersampling, and therefore conformant: each
    // pass interpolates at the PIXEL CENTRE and shades identically, and only
    // the coverage test and the depth/stencil write target the pass's own
    // sample. The shaded value is the same every pass, so the accumulated
    // average is exactly the MSAA resolve. Vulkan requires the fragment
    // shader to run *at least* max(ceil(minSampleShading * rasterizationSamples), 1)
    // times per fragment; running it more often is permitted.
    //
    // The semantics multi-pass WOULD disturb -- interpolateAtSample,
    // gl_SampleID, the `sample` qualifier -- are all gated behind the
    // optional sampleRateShading feature, which borgvk reports false
    // (borgvk_device.c advertises only textureCompressionETC2), so the CTS
    // cases covering them are skipped.
    //
    // LATENT CONSTRAINT: a fragment shader with storage writes or atomics
    // would execute them four times instead of once, which IS observable.
    // Borg has no fragment load/store today; adding it later would silently
    // break conformance unless this is revisited.
    msaaMultiPass: Boolean = false,
    // --- Sequencer configuration pipelining -----------------------------
    //
    // The sequencer's MMIO configuration (base addresses, shader pointers,
    // clear colour, tile geometry) is registered next to the bus decode in
    // Borg and read by BorgSequencer, which the placer puts far away. Measured
    // on run `msaa-fix` 2026-09-22:
    //
    //   seqSetupBaseReg  1,939 um, and 22 of its 25 bits exceed 800 um
    //   antenna violators median span 715 um vs 47 um for all 185,982 nets
    //   40% of violators exceed 1,000 um; only 1.5% of all nets do
    //
    // These nets are POINT-TO-POINT (one consumer each), so neither register
    // replication nor `dontTouch` can shorten them -- and Borg.scala's own
    // reset-copy comment records that yosys merges identical registers back
    // together regardless of dontTouch. A pipeline stage is different in kind:
    // it splits each net in two, and each stage has a single consumer so there
    // is nothing for opt_merge to undo.
    //
    // Safe because every field is STATIC configuration, written during setup
    // and read during rendering. `start` is delayed with the rest, so the
    // relative timing the sequencer sees is unchanged -- only the absolute
    // latency from the MMIO write grows by one cycle, which no firmware
    // observes (it polls `done`).
    pipelineSeqConfig: Boolean = false,
    // --- Extended ISA -------------------------------------------------
    //
    // Two knobs rather than one so the wafer.space area/feature tradeoff can
    // be measured at finer grain than all-or-nothing. Both default TRUE:
    // unlike the fixed-function knobs above, these gate instructions a
    // compiler may already have emitted into a shader binary, and silently
    // dropping an opcode would execute as something else rather than fail.
    // Turning one off is an explicit decision to ship a smaller ISA.
    //
    // hasMemoryOps: LOAD/STORE and the core's DRAM port. The prerequisite
    // for compute queues, SSBOs and storage images -- but dead area for a
    // target that only ever runs the graphics pipeline.
    hasMemoryOps: Boolean = true,
    // hasControlFlow: BRZ/BRNZ plus the execution mask and its stack. Costs
    // the PC redirect, the mask, and 8 x fragLanes bits of stack. At
    // fragLanes=1 the mask is degenerate but the branches are not -- loops
    // and early exits need them regardless of SIMT width.
    hasControlFlow: Boolean = true,
    // hasCompute: the compute dispatch sequencer (BorgComputeSequencer) and
    // compute-mode invocation IDs in r30/r31. Only takes effect with LOAD/STORE
    // (the only way a compute shader produces output), the execution mask
    // (partial quads) and a 32-bit datapath (the IDs are raw integers) -- see
    // computeEnabled.
    hasCompute: Boolean = false,
    // hasShaderICache: IMEM becomes a direct-mapped instruction cache over a
    // program image in DRAM, so a shader is no longer limited to
    // maxInstructions words. Vulkan has no shader-size limit, and every
    // Vulkan-class GPU fetches shader code from memory; a fixed on-chip IMEM
    // is where GLES2-era cores (Vivante GC2000, whose driver rejects any
    // shader that does not fit) stopped.
    //
    // Preloading is unchanged -- the sequencer's shader DMA and MMIO writes
    // fill IMEM exactly as before -- so a shader that fits never misses and
    // runs as it always did. A PC past the preloaded words misses, fetches
    // `codeBase + 4*pc` through the core's LOAD/STORE port, and keeps the
    // word for the next quad. Costs a tag and a valid bit per line and the
    // fill FSM; no extra instruction storage. Needs hasMemoryOps (the port).
    hasShaderICache: Boolean = false,
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
  // Fixed at exactly 4: hardware/rdl/borg.rdl declares tex_base_addr0..3 as
  // four separate named registers (not a SystemRDL array -- see that file's
  // own comment for why), so Borg.scala's mux over them is hardcoded to 4
  // wide. Raising this needs both files changed together, not just this one.
  require(maxTextureBindings == 4, s"maxTextureBindings must be 4 (matching the RDL's fixed tex_base_addr0..3), got $maxTextureBindings")
  require(fragLanes == 1 || fragLanes == 4, s"fragLanes must be 1 or 4, got $fragLanes")
  require(samples == 1 || samples == 4, s"samples must be 1 or 4, got $samples")
  require(!msaaMultiPass || samples > 1,
          "msaaMultiPass is meaningless at samples == 1 (there is nothing to accumulate)")
  require(tileColorBits == 16 || tileColorBits == 8,
          s"tileColorBits must be 16 (off) or 8 (ColorQuantize UNORM8), got $tileColorBits")
  require(fmaStages >= 3 && fmaStages <= 5, s"fmaStages must be 3, 4 or 5, got $fmaStages")
  // Compute exists only where its prerequisites do; the driver detects it
  // through COMPUTE_CTRL's `present` bit rather than assuming it.
  def computeEnabled: Boolean = hasCompute && hasMemoryOps && hasControlFlow && fp.totalBits == 32
  def shaderICacheEnabled: Boolean = hasShaderICache && hasMemoryOps
  /** The draw front end (docs/B1_geometry_front_end.md): SOUT/FATTR, and
    * VertexIndex/InstanceIndex in r30/r31. Needs the core's memory port, and
    * a 32-bit datapath because the indices are raw integers. */
  def drawEnabled: Boolean = hasMemoryOps && fp.totalBits == 32
  /** r30/r31 can carry sequencer-provided integer IDs instead of the pixel
    * centre: compute's invocation IDs, or a vertex shader's indices. */
  def hasInvocationIds: Boolean = computeEnabled || drawEnabled
  /** The descriptor-based texture unit, TEX/TEXA (docs/B2_texture_unit.md):
    * FP32 results, the core's memory port. */
  def samplerEnabled: Boolean = drawEnabled
  /** Planes whose MSAA sample deltas travel with a triangle: the three edges,
    * plus the depth plane Zn on a draw-front-end build (the far plane's are
    * Zn's, negated). */
  def coveragePlanesStored: Int = if (drawEnabled) 4 else 3
  /** Tile-buffer depth width. Follows the datapath: an FP32 build keeps depth
    * FP32 end to end, which holds every D16/D24 value distinctly and
    * D32_SFLOAT exactly; FP16 cannot (see ColorZ). */
  def tileDepthBits: Int = if (fp.totalBits == 32) 32 else 16
  /** Program counter width. With the instruction cache a program may run past
    * IMEM, up to 16K words: conditional branches reach their own 1024-word
    * page, JMP anywhere (Instructions.FUNCT7_JMP). Vulkan sets no shader size
    * limit, and a shader with software division, bounds checks and compares
    * outgrew 1024 words. Without the cache the PC only ever indexes IMEM. */
  def pcBits: Int = if (shaderICacheEnabled) 14 else chisel3.util.log2Ceil(maxInstructions)
  /** Direct-mapped cache lines: the largest power of two within IMEM. Lines
    * at and above it (IMEM 64..71 on a 72-word build) are never replaced. */
  def icacheLinesLog2: Int = chisel3.util.log2Floor(maxInstructions)
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig

  // --- busy_counter phase constants, derived from fmaStages ---------------
  //
  // BorgCore runs one instruction as a countdown (see its "Pipeline timing"
  // doc). Every extra FMA pipeline stage inserts a cycle, so the phases move.
  // These are the single source of truth for both BorgCore and BorgLane --
  // the counter values used to live as literals in both files, which is why
  // BorgConfig's fmaStages comment warned that raising it "alone is NOT
  // functionally correct".
  //
  // fmaStages=4 splits BorgFp16Fma stage 2 (reg2a, between regMid and magR),
  // so everything from operand-read down to pipeEn1 shifts one cycle later
  // while pipeEn2 and write-back stay put. fmaStages=5 additionally splits
  // stage 3 (the y_* registers after magR), which pushes pipeEn2 out too.
  //
  //            stages=3   stages=4   stages=5
  //   load/rs2      7          8          9
  //   rs3           6          7          8
  //   holdC         5          6          7
  //   pipeEn1       4          5          6     (= operands valid)
  //   pipeEn2       3          3          4
  //   write-back    1          1          1
  private def fmaExtraMid: Int = if (fmaStages >= 4) 1 else 0
  private def fmaExtraLate: Int = if (fmaStages >= 5) 1 else 0

  /** pipeEn2: magR capture. */
  def cPipeEn2: Int = 3 + fmaExtraLate
  /** pipeEn1 / all three operands held and valid -- also when BorgCore starts
    * FTEX, LOAD/STORE and evaluates branches. */
  def cOperands: Int = cPipeEn2 + 1 + fmaExtraMid
  def cHoldC: Int = cOperands + 1
  def cRs3: Int = cOperands + 2
  def cRs2: Int = cOperands + 3
  /** Value busy_counter loads when an instruction issues. */
  def cBusyLoad: Int = cRs2
  def busyCounterWidth: Int = chisel3.util.log2Ceil(cBusyLoad + 1)
}

object BorgConfig {
  // Default: sim + ULX3S — 4096-tile bin table, 56-instruction shader memory.
  // The in-tree BorgFp16Fma (CERN-OHL-S, round-to-nearest-even) is the sole FP16 FMA
  // across ALL targets — historically bit-verified vs IEEE/HardFloat (30k+ co-sim),
  // renders correctly in verilator/arcilator/ULX3S, smaller + shorter critical path.
  //
  // maxBinTiles = 4096 (grown from 1024, 2026-09-08, Step 50 item 5 --
  // framebuffer/image resolution ceiling): covers up to 256x256 @ 4x4
  // (64x64 = 4096 tiles), 4x the previous 128x128 capacity. This is a real,
  // conservative step, not the full Vulkan-mandated >=4096x4096 --
  // reaching that needs either a much larger capacity (the per-buffer
  // tileWasDirty/tileIsDirty dirty-bit arrays in BorgTileSequencer cost 2
  // flip-flops per tile, so scaling all the way to 4096x4096 pixels
  // (1024x1024 = 1,048,576 tiles) would cost ~2M FFs -- not a number to
  // pick without real synthesis data) or firmware-side multi-pass tiling
  // (re-running the existing binner/render pass per tile-batch) on top of
  // whatever capacity is here. See docs/A0_roadmap.md item 8's own note.
  // This growth is backward compatible: maxBinTiles is a capacity ceiling,
  // not a required resolution -- firmware requesting the previous 128x128
  // (1024 of the now-4096 available tile slots) behaves identically to
  // before, verified by the unchanged 195/195 mill hardware.borg.test pass
  // and the vkcube golden-image render (both still rendering the same
  // 128x128 content). log2Ceil(4096)=12 stays under SeqBinnerIO/
  // BorgBinnerIO's existing countAddrWidth cap of 13 bits, so no other RTL
  // needed changing for this specific step -- a bigger future jump past
  // 8192 tiles would need that cap raised too (see those IOs' own comments).
  // What Vulkan needs, on every target. Default is the single place the
  // feature set is decided; every other config below is a `Default.copy`
  // that changes SIZING only (tile capacity, IMEM, caches, lanes), never
  // the float format, sample count or fixed-function feature set. A target
  // that wants a different feature set must say so at its own `.copy` --
  // there is deliberately no second base config to drift from this one
  // (BorgConfig.Asic used to be that, hardcoded FP16, and the wafer.space
  // tapeout silently kept building FP16 after Default moved to FP32).
  //
  //   fp = FP32:      Vulkan's baseline Shader capability mandates 32-bit
  //                   float arithmetic unconditionally (no feature bit gates
  //                   it, unlike shaderFloat16).
  //   samples = 4:    framebufferColorSampleCounts must include
  //                   VK_SAMPLE_COUNT_4_BIT.
  //   hasDepthFlush:  D16_UNORM is a mandatory depth format, and without the
  //                   flush Z never leaves the tile buffer, so no depth image
  //                   can exist. Resolves to sample zero at MSAA (see the
  //                   parameter's doc).
  //   hasBlend:       colour blending is core Vulkan (only independentBlend
  //                   and dualSrcBlend are optional).
  //   hasStencil:     the stencil test is core; no feature bit gates it.
  //   hasBilinear:    VK_FILTER_LINEAR is core; no feature bit gates it.
  //   Every one of the four is runtime-gated by its own enable bit, so a
  //   build with them on behaves exactly like one without until firmware
  //   turns a feature on -- the cost is area, never behaviour.
  //
  // 2026-09-15: switched deliberately ahead of re-validating area/timing at
  // this combination (FP32 FMA alone measured 2.48x area / 1.79x critical
  // path via yosys in isolation; samples=4 alone is proven at ASIC via a
  // real 1x0.5 signoff at 85.68% -- the combination, on the wafer.space 1x1
  // slot and on ULX3S, is unmeasured as of this change).
  val Default = BorgConfig(
    fp              = FloatConfig.FP32,
    coordWidth      = 9,
    fifoDepth       = 2,
    maxBinTiles     = 4096,
    maxInstructions = 72, // M5 step 1: grow IMEM (rast 13 + frag ~56 co-resident)
    samples         = 4,
    hasDepthFlush   = true,
    hasBlend        = true,
    hasStencil      = true,
    hasBilinear     = true,
    hasCompute      = true,
    hasShaderICache = true
  )

  // Sim + ULX3S SIMT config: 2×2 quad fragment shading.  Selected via BORG_CFG in
  // the sim tops and ULX3S; the scalar Default keeps the chisel unit tests on
  // the bit-exact single-lane reference.  maxBinTiles=4096 covers up to
  // 256×256 @ 4×4 (64×64 = 4096 tiles); the current demo resolution
  // (128×128) uses only 1024 of that capacity, unaffected by the growth
  // (see Default's own comment for the full rationale).
  val Simt = Default.copy(fragLanes = 4, maxBinTiles = 4096)

  // --- Simulation configs for validating msaaMultiPass end to end ---------
  //
  // A fair comparison needs BOTH sides quantized: msaaMultiPass requires
  // tileColorBits = 8, so measuring it against the plain Simt (which stores
  // FP16) would show colour-quantization differences and call them MSAA.
  // These two differ ONLY in where the samples live.
  //
  //   SimtQ    resident samples, UNORM8 tile colour
  //   SimtQMp  one live plane + accumulator, UNORM8 tile colour
  //
  // Both render 4x MSAA and average the same four sample colours -- SimtQ in
  // the flusher, SimtQMp in the accumulator -- so a correct implementation
  // renders the SAME image. Any divergence is a real bug in the pass loop,
  // the accumulator or the resolve.
  val SimtQ   = Simt.copy(tileColorBits = 8)
  val SimtQMp = SimtQ.copy(msaaMultiPass = true)
  /** SimtQMp plus the sequencer configuration pipeline, so `pipelineSeqConfig`
    * gets a real end-to-end render rather than elaboration coverage alone.
    * A correct pipeline is invisible: the rendered frame must be identical. */
  val SimtQMpP = SimtQMp.copy(pipelineSeqConfig = true)

  /** Which config the Verilator/Arcilator sim tops elaborate, from
    * `BORG_SIM_CFG`. Default `simt` keeps every existing sim run unchanged.
    *
    * MUST be used by BorgSimTop AND BorgSimMain's Peripherals: firtool splits
    * per module, so two different configs in one emission silently overwrite
    * each other's BorgCore/BorgShaderDispatcher (see BorgSimMain's own note).
    */
  def simCfg: BorgConfig = sys.env.getOrElse("BORG_SIM_CFG", "simt") match {
    case "simt"    => Simt
    case "simt8"   => SimtQ
    case "simt8mp" => SimtQMp
    case "simt8mpp" => SimtQMpP
    case other     => throw new IllegalArgumentException(
      s"BORG_SIM_CFG=$other; expected simt, simt8, simt8mp or simt8mpp")
  }

  // The ASIC: wafer.space GF180MCU, 1x1 slot, via BorgOnlyTop (the Borg-only
  // bridge; QspiSocTop, the cocotb CPU SoC harness, builds from this too).
  // Default's feature set, sized down to fit the slot. Every line here is a
  // SIZING decision with a measured reason; nothing about fp/samples/features
  // is repeated here, so the tapeout cannot fall behind Default again.
  //   maxBinTiles=16: countMem_1024x10 alone was ~920 kµm² (50 % of the TT
  //     die) at 1024 tiles → 16 tiles (~14 kµm²).
  //   maxInstructions=64: the rasterizer edge-test shader (13 words) no longer lives
  //     in this writable IMEM at all -- it's baked into a permanent ROM (BorgRasterRom),
  //     fetched by BorgCore independently. This budget is now frag-only: cube.frag
  //     is 59 words, +1 word BORG_IMEM_FRAG_OFFSET (kept nonzero so fragPcReg==0 can
  //     still mean "no fragment shader"), +1 HALT sentinel = 61 of 64 used.
  //   icacheLines=0: I-cache bypassed — at 4 MHz QSPI latency is trivial; saves ~55 kµm².
  //   maxUniforms=32: single-page uniforms — sequencer always writes page 0; saves ~25 kµm².
  //   hasPerfCounters=false: 5×32-bit counters not needed for silicon demo; saves ~18 kµm².
  //   fragLanes=4 (+ Default's samples=4): 4-lane SIMT and 4x MSAA. Verified by a
  //     full wafer.space 1x0.5 signoff at FP16 -- 85.68 % utilisation, DRC/LVS/antenna
  //     clean, 4.52 mW. The earlier 0.5x1 orientation failed detailed placement
  //     (DPL-0036) at 81.97 %; 1x0.5 is the orientation that fits. Real Max Slew /
  //     Max Cap warnings remain outstanding -- electrical, not frequency-related.
  //   tileColorBits=8: BorgTileBuffer stores R/G/B as UNORM8 (via
  //     ColorQuantize) instead of full FP16, quantizing on write and
  //     dequantizing on read entirely internally -- TileWriteIO/TileReadIO
  //     stay FP16 at the port, so nothing outside BorgTileBuffer changes. Z
  //     stays FP16 (never quantized -- see BorgConfig.tileColorBits's own doc
  //     for why). Measured: rgbzMems_16x64 -> rgbzMems_16x40, -37.2% per MSAA
  //     sample plane, -8.75% (2,323,169 -> 2,119,850 um^2) on the whole
  //     BorgOnlyCore hierarchy after the quantizer/dequantizer's own added
  //     logic is accounted for. Verified: full hardware.borg.test (195/195)
  //     at both tileColorBits=16 (unaffected) and =8 (new dedicated tests in
  //     BorgTileBufferTests/ColorQuantizeTests), incl. the full render-pipeline
  //     end-to-end tests and MSAA per-sample coverage masking against the
  //     narrower storage.
  //   debugPorts=false: BorgOnlyTop has no SoCLogic/CPU harness to expose the
  //     covDeltaDebug tap through (nor the TT-pad-only uo_out/user_interrupt).
  //
  // FP32 at this sizing: Phase 0 measured Wafer at FP32 via yosys at 2.48x FMA
  // area, 58-62% 1x1-slot utilization, and 25 MHz closing at 3.3V with the
  // original 3-stage pipeline. A real signoff at FP32 + depth flush is the
  // open item as of 2026-09-15.
  val Wafer = Default.copy(
    coordWidth       = 7,
    maxBinTiles      = 16,
    maxInstructions  = 64,
    icacheLines      = 0,
    maxUniforms      = 32,
    hasPerfCounters  = false,
    fragLanes        = 4,
    tileColorBits    = 8,
    debugPorts       = false,
    // 2026-09-19: the ONLY target that needs this. Measured post-repair with
    // OpenSTA at max_ss_125C_3v00 on two signoff runs, the worst path was
    // BorgFp16Fma stage 2 -- m_prodLowExp -> magR, the three serial carry
    // chains -- at -5.6 ns and -12.8 ns against the 125 ns budget. That is
    // exactly the stage fmaStages=4 splits. Costs one cycle per FMA; the
    // ULX3S/sim Default stays at 3 because the FMA is not its limiter.
    fmaStages        = 4,
    // 2026-09-21: the ONLY target that needs this either. Measured on GF180,
    // rendering each tile once per sample instead of keeping every sample
    // resident saves 346,969 um2 -- 9.32% of the whole design:
    //
    //   tile memories  12 -> 4     536,814 -> 189,845 um2  (-64.6%)
    //   stored bits    3,584 -> 1,376
    //
    // The cost is roughly 4x the fragment work, which this target can afford
    // and the ULX3S/sim Default cannot: the wafer.space submission is gated
    // on fitting and routing, not on frame rate. Conformance is unaffected --
    // see msaaMultiPass's own doc for why this is still MSAA.
    msaaMultiPass    = true,
    // Splits the longest nets in the design (see the parameter's own doc).
    // Costs one cycle of MMIO-write latency, which nothing observes.
    pipelineSeqConfig = true
    // Compute is on, as in Default: Vulkan requires a queue family with
    // compute. It was off only while the 1x1 slot was tight.
  )

  // The config Chisel unit tests should instantiate full Borg/BorgTestWrapper
  // with, unless a test genuinely needs more tile capacity.
  //
  // maxBinTiles is the single dominant cost of building a full-Borg simulation.
  // BorgTileSequencer tracks 2 dirty bits per tile per buffer, and firtool
  // emits every element of those Vecs as its own `reg`: at 4096 tiles that is
  // 16,384 one-bit registers, making BorgTileSequencer.sv 125,746 of the
  // design's 133,899 emitted lines -- 94% of the whole design, when every
  // other module is 300-1300 lines. Verilator's V3Order then topologically
  // sorts a dependency graph over all of it, single-threaded.
  //
  // Measured A/B, same seven scenarios, only this parameter changed:
  //   maxBinTiles = 4096 -> 1092s
  //   maxBinTiles =   64 ->   13s     (84x, bit-for-bit identical results)
  //
  // This costs no coverage: maxBinTiles is a capacity ceiling, not a
  // resolution (see Default's comment), so a design binning fewer tiles than
  // the ceiling behaves identically -- which the A/B confirms empirically.
  // Raise it per-test only where a test really bins more than 64 tiles; a
  // 128x128 framebuffer at 4x4 tiles, for instance, needs 1024.
  val Test = Default.copy(maxBinTiles = 64)
}
