// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Build-time configuration for the Borg GPU.
  *
  * Encapsulates all target-specific knobs so that a single parameter
  * flows through the entire hierarchy.
  *
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
    coordWidth: Int = 9,
    fifoDepth: Int = 2,
    maxBinTiles: Int = 1024,
    maxInstructions: Int = 56,
    icacheLines: Int = 512,
    // Draw front end: FATTR's varying cache, 2^fattrCacheLog2 words. Without it a fragment
    // shader re-reads each triangle's per-vertex values from memory for
    // every quad -- cube.frag's 5 FATTRs are 15 serialized single-word DRAM
    // round trips per quad. 256 words hold all 12 of vkcube's triangles'
    // varyings; measured on a draw-mode vkcube frame, 2,389,185 -> 2,123,635
    // cycles (stalls 453K -> 168K).
    fattrCacheLog2: Int = 8,
    // TEX keeps 2^texDescCacheLog2 texture descriptors (a vertex shader reads one per input).
    texDescCacheLog2: Int = 2,
    maxUniforms: Int = 64,
    fragLanes: Int = 1,
    maxTrianglesPerTile: Int = 256,
    samples: Int = 1,
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
    hasShaderICache: Boolean = false
) {
  require(fragLanes == 1 || fragLanes == 4, s"fragLanes must be 1 or 4, got $fragLanes")
  require(samples == 1 || samples == 4, s"samples must be 1 or 4, got $samples")
  // Compute exists only where its prerequisites do; the driver detects it
  // through COMPUTE_CTRL's `present` bit rather than assuming it.
  def computeEnabled: Boolean = hasCompute && hasMemoryOps && hasControlFlow
  def shaderICacheEnabled: Boolean = hasShaderICache && hasMemoryOps
  def fattrCacheEnabled: Boolean = drawEnabled
  /** The draw front end (docs/B1_geometry_front_end.md): SOUT/FATTR, and
    * VertexIndex/InstanceIndex in r30/r31. Needs the core's memory port, and
    * a 32-bit datapath because the indices are raw integers. */
  def drawEnabled: Boolean = hasMemoryOps
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
  def tileDepthBits: Int = 32
  /** Program counter width. With the instruction cache a program may run past
    * IMEM, up to 16K words: conditional branches reach their own 1024-word
    * page, JAL anywhere (Opcodes.MATCH_JAL). Vulkan sets no shader size
    * limit, and a shader with software division, bounds checks and compares
    * outgrew 1024 words. Without the cache the PC only ever indexes IMEM. */
  def pcBits: Int = if (shaderICacheEnabled) 14 else chisel3.util.log2Ceil(maxInstructions)
  /** Direct-mapped cache lines: the largest power of two within IMEM. Lines
    * at and above it (IMEM 64..71 on a 72-word build) are never replaced. */
  def icacheLinesLog2: Int = chisel3.util.log2Floor(maxInstructions)
  // Line state in a RAM instead of flops once flops would cost more than a RAM.
  def icacheMetaInRam: Boolean = maxInstructions > 128
  /** The datapath is FP32: Vulkan's minimum. */
  def fp: FloatConfig = FloatConfig.FP32
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig

  // --- Pipeline stages (BorgCore's "Pipeline" doc), counted down to write-back at 1 ---
  /** pipeEn2: magR capture. */
  def cPipeEn2: Int = 3
  /** pipeEn1 / all three operands held and valid -- also when BorgCore starts
    * TEX/TEXA, LOAD/STORE and evaluates branches. */
  def cOperands: Int = 4
  /** Issue: rs1, rs2 and rs3 read, held here. */
  def cHoldC: Int = 5
  /** Stages an instruction passes through after issue. */
  def cBusyLoad: Int = cHoldC
  def busyCounterWidth: Int = chisel3.util.log2Ceil(cBusyLoad + 1)
}

object BorgConfig {
  // Default: sim + ULX3S — 4096-tile bin table, 56-instruction shader memory.
  // The in-tree BorgFma (CERN-OHL-S, round-to-nearest-even) is the sole FMA
  // across ALL targets, FP16 or FP32 — historically bit-verified vs IEEE/HardFloat (30k+ co-sim),
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
  //   Every one of the three is runtime-gated by its own enable bit, so a
  //   build with them on behaves exactly like one without until firmware
  //   turns a feature on -- the cost is area, never behaviour.
  //
  // 2026-09-15: switched deliberately ahead of re-validating area/timing at
  // this combination (FP32 FMA alone measured 2.48x area / 1.79x critical
  // path via yosys in isolation; samples=4 alone is proven at ASIC via a
  // real 1x0.5 signoff at 85.68% -- the combination, on the wafer.space 1x1
  // slot and on ULX3S, is unmeasured as of this change).
  val Default = BorgConfig(
    coordWidth      = 9,
    fifoDepth       = 2,
    maxBinTiles     = 4096,
    maxInstructions = 512,
    samples         = 4,
    hasDepthFlush   = true,
    hasBlend        = true,
    hasStencil      = true,
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

  // Smallest config that still runs the draw front end (FP32 + shader I-cache):
  // one lane, 64 bin tiles (32x32 px), no MSAA/blend/stencil/compute/depth flush.
  // For fitting beside RV64 Linux on the ECP5-85K; derivatives read 0 at one lane.
  val Tiny = Default.copy(fragLanes = 4, maxBinTiles = 1024, samples = 1, hasBlend = false,
                          hasStencil = false, hasCompute = false, hasDepthFlush = false)

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
  //   maxUniforms=32: single-page uniforms — sequencer always writes page 0; saves ~25 kµm².
  //   fragLanes=4 (+ Default's samples=4): 4-lane SIMT and 4x MSAA. Verified by a
  //     full wafer.space 1x0.5 signoff at FP16 -- 85.68 % utilisation, DRC/LVS/antenna
  //     clean, 4.52 mW. The earlier 0.5x1 orientation failed detailed placement
  //     (DPL-0036) at 81.97 %; 1x0.5 is the orientation that fits. Real Max Slew /
  //     Max Cap warnings remain outstanding -- electrical, not frequency-related.
  //
  // FP32 at this sizing: Phase 0 measured Wafer at FP32 via yosys at 2.48x FMA
  // area, 58-62% 1x1-slot utilization, and 25 MHz closing at 3.3V with the
  // original 3-stage pipeline. A real signoff at FP32 + depth flush is the
  // open item as of 2026-09-15.
  val Wafer = Default.copy(
    coordWidth       = 7,
    maxBinTiles      = 16,
    maxInstructions  = 64,
    maxUniforms      = 32,
    fragLanes        = 4
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
