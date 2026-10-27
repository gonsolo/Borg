// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Build-time configuration for the Borg GPU: sizes, plus the features the
  * ULX3S leaves out to fit.
  *
  * @param coordWidth      Pixel coordinate bits: framebuffer up to 2^coordWidth.
  * @param fifoDepth       Command FIFO depth.
  * @param maxBinTiles     4x4-pixel tiles the binner tracks: 1024 = 128x128, 4096 = 256x256.
  * @param maxInstructions Shader I-cache words; programs run from memory through it.
  * @param fattrCacheLog2  FATTR's varying cache, 2^n words (vkcube: 2.39M -> 2.12M cycles).
  * @param texDescCacheLog2 Texture descriptors TEX keeps.
  * @param maxUniforms     Uniform words: 64 = two pages, 32 = one.
  * @param maxTrianglesPerTile At least SEQ_MAX_TRI in software/borg/borg_layout.h.
  * @param samples         1 or 4 (Vulkan requires 4x MSAA).
  * @param hasDepthFlush   Tile depth written to memory: the only way a depth image exists.
  * @param hasBlend        Colour blending, RAW formats and the tile's extension plane.
  * @param hasStencil      Stencil plane and test.
  * @param hasCompute      Compute dispatch and its invocation IDs in r30/r31.
  * The fixed-function features are gated at run time by their enable bits too,
  * so a build with them behaves like one without until the driver turns them on.
  */
case class BorgConfig(
    coordWidth: Int = 9,
    fifoDepth: Int = 2,
    maxBinTiles: Int = 1024,
    maxInstructions: Int = 56,
    fattrCacheLog2: Int = 8,
    texDescCacheLog2: Int = 2,
    maxUniforms: Int = 64,
    maxTrianglesPerTile: Int = 256,
    samples: Int = 1,
    hasDepthFlush: Boolean = false,
    hasBlend: Boolean = false,
    hasStencil: Boolean = false,
    hasCompute: Boolean = false
) {
  require(samples == 1 || samples == 4, s"samples must be 1 or 4, got $samples")
  /** Planes whose MSAA sample deltas travel with a triangle: the three edges
    * and the depth plane Zn (the far plane's are Zn's, negated). */
  def coveragePlanesStored: Int = 4
  /** FP32 depth holds every D16/D24 value and D32_SFLOAT exactly. */
  def tileDepthBits: Int = 32
  /** 16K-word programs: branches reach their 1024-word page, JAL anywhere. */
  def pcBits: Int = 14
  /** Direct-mapped cache lines: the largest power of two within IMEM. Lines
    * at and above it (IMEM 64..71 on a 72-word build) are never replaced. */
  def icacheLinesLog2: Int = chisel3.util.log2Floor(maxInstructions)
  // Line state in a RAM instead of flops once flops would cost more than a RAM.
  def icacheMetaInRam: Boolean = maxInstructions > 128
  /** The datapath is FP32: Vulkan's minimum. */
  def fp: FloatConfig = FloatConfig.FP32
  def totalBits: Int = fp.totalBits
  /** A 2x2 pixel quad per shader pass: DDX/DDY (and Vulkan's derivatives) need it. */
  def fragLanes: Int = 4
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
  // What Vulkan 1.0 needs: the simulators, and the base of every other config.
  val Default = BorgConfig(
    coordWidth      = 9,
    fifoDepth       = 2,
    maxBinTiles     = 4096,
    maxInstructions = 512,
    samples         = 4,
    hasDepthFlush   = true,
    hasBlend        = true,
    hasStencil      = true,
    hasCompute      = true
  )

  // The ULX3S: what unmodified cube.c uses; the full set is ~13% over the ECP5-85K.
  // 1024 bin tiles cover its 128x128 framebuffer.
  val Cube = Default.copy(maxBinTiles = 1024, samples = 1, hasBlend = false,
                          hasStencil = false, hasCompute = false, hasDepthFlush = false)

  // The ASIC (wafer.space GF180MCU 1x1, BorgOnlyTop; QspiSocTop too): Default sized down.
  // cube.frag is 59 words.
  val Wafer = Default.copy(
    coordWidth       = 7,
    maxBinTiles      = 16,
    maxInstructions  = 64,
    maxUniforms      = 32
  )

  // Unit tests: the tile dirty bits dominate elaboration (4096 tiles: 1092 s, 64: 13 s).
  val Test = Default.copy(maxBinTiles = 64)
}
