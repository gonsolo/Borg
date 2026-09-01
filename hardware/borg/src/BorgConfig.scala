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
    samples: Int = 1
) {
  require(fragLanes == 1 || fragLanes == 4, s"fragLanes must be 1 or 4, got $fragLanes")
  require(samples == 1 || samples == 4, s"samples must be 1 or 4, got $samples")
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
    samples          = 4
  )
}
