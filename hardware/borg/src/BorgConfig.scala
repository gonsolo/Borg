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
  */
case class BorgConfig(
    fp: FloatConfig = FloatConfig.FP16,
    coordWidth: Int = 9,
    fifoDepth: Int = 2,
    maxBinTiles: Int = 1024,
    maxInstructions: Int = 56,
    icacheLines: Int = 512
) {
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig
}

object BorgConfig {
  // Default: sim + ULX3S — full 1024-tile bin table, 56-instruction shader memory.
  val Default = BorgConfig(
    fp              = FloatConfig.FP16,
    coordWidth      = 9,
    fifoDepth       = 2,
    maxBinTiles     = 1024,
    maxInstructions = 56
  )

  // ASIC (IHP SG13G2, TT 8×4 tile).
  //   countMem_1024x10 alone was ~920 kµm² (50 % of die) → reduced to 16 tiles (~14 kµm²).
  //   instructionMemory_56x32 was ~145 kµm² → reduced to 32 entries (~83 kµm²).
  //   Total savings: ~968 kµm², target utilisation ≈ 69 %.
  val Asic = BorgConfig(
    fp              = FloatConfig.FP16,
    coordWidth      = 9,
    fifoDepth       = 2,
    maxBinTiles     = 16,
    maxInstructions = 32,
    icacheLines     = 32
  )
}
