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
  *                        ASIC uses 32 — the demo shader fits comfortably.
  * @param maxUniforms  Uniform memory depth.  64 = two 32-entry pages (double-buffered
  *                     for CPU/GPU overlap); 32 = single page (saves ~25 kµm² on ASIC
  *                     where the sequencer always writes page 0).
  * @param hasPerfCounters Wire up the 5×32-bit GPU performance counters (total/frag/
  *                     flush/stall/dma).  Useful for fps profiling on ULX3S; omitted
  *                     on ASIC to save ~18 kµm².
  * @param useCustomFma Select the FP16 fused-multiply-add implementation.  false =
  *                     Berkeley HardFloat MulAddRecFN (full IEEE-754, proven); true =
  *                     in-tree BorgFp16Fma (GPU domain, round-to-nearest-even, CERN-OHL-S).
  *                     The custom unit is smaller and shorter critical-path (enables a
  *                     clock bump) and is the per-lane datapath for SIMT.
  * @param fragLanes  Fragment-shader SIMT width.  1 = scalar (one pixel per shader pass,
  *                     the current behaviour); 4 = a 2×2 pixel quad per pass (4× shader
  *                     throughput, lays the architecture for dFdx/dFdy).  Only the
  *                     ULX3S/sim targets use 4; pico-ice/ASIC stay at 1 (area).
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
    useCustomFma: Boolean = false,
    fragLanes: Int = 1
) {
  require(fragLanes == 1 || fragLanes == 4, s"fragLanes must be 1 or 4, got $fragLanes")
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig
}

object BorgConfig {
  // Default: sim + ULX3S — full 1024-tile bin table, 56-instruction shader memory.
  // useCustomFma=true: the in-tree BorgFp16Fma is bit-verified vs HardFloat (30k+
  // co-sim cases) and renders correctly in verilator AND arcilator AND on ULX3S
  // hardware; it is smaller and shorter-critical-path.  (The "arcilator miscompile"
  // once attributed to it was a missing-firmware misdiagnosis — see
  // docs/arcilator_custom_fma_bug.md.)  The Asic config below stays on HardFloat so
  // the already-validated/taped-out GDS is unaffected.
  val Default = BorgConfig(
    fp              = FloatConfig.FP16,
    coordWidth      = 9,
    fifoDepth       = 2,
    maxBinTiles     = 1024,
    maxInstructions = 56,
    useCustomFma    = true
  )

  // Sim + ULX3S SIMT config: 2×2 quad fragment shading.  Selected via BORG_CFG in
  // the sim tops and ULX3S; the scalar Default keeps the chisel unit tests (and
  // pico-ice) on the bit-exact single-lane reference.
  val Simt = Default.copy(fragLanes = 4)

  // ASIC (IHP SG13G2, TT 8×4 tile).
  //   countMem_1024x10 alone was ~920 kµm² (50 % of die) → reduced to 16 tiles (~14 kµm²).
  //   instructionMemory_56x32 was ~145 kµm² → reduced to 32 entries (~83 kµm²).
  //   icacheLines=0: I-cache bypassed — at 4 MHz QSPI latency is trivial; saves ~55 kµm².
  //   maxUniforms=32: single-page uniforms — sequencer always writes page 0; saves ~25 kµm².
  //   hasPerfCounters=false: 5×32-bit counters not needed for silicon demo; saves ~18 kµm².
  val Asic = BorgConfig(
    fp               = FloatConfig.FP16,
    coordWidth       = 9,
    fifoDepth        = 2,
    maxBinTiles      = 16,
    maxInstructions  = 32,
    icacheLines      = 0,
    maxUniforms      = 32,
    hasPerfCounters  = false,
    useCustomFma     = false  // explicit: keep HardFloat for the validated/taped-out GDS
  )
}
