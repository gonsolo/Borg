// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Build-time configuration for the Borg GPU: sizes, plus the features the
  * ULX3S leaves out to fit.
  *
  * @param coordWidth      Pixel coordinate bits: framebuffer up to 2^coordWidth.
  * @param maxBinTiles     4x4-pixel tiles the binner tracks: 1024 = 128x128, 4096 = 256x256.
  * @param maxInstructions Shader I-cache words; programs run from memory through it.
  * @param maxUniforms     Uniform words: 64 = two pages, 32 = one.
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
    maxBinTiles: Int = 4096,
    maxInstructions: Int = 512,
    maxUniforms: Int = 64,
    samples: Int = 4,
    hasDepthFlush: Boolean = true,
    hasBlend: Boolean = true,
    hasStencil: Boolean = true,
    hasCompute: Boolean = true
) {
  require(samples == 1 || samples == 4, s"samples must be 1 or 4, got $samples")
  def fifoDepth: Int = 2
  def fattrCacheLog2: Int = 8           // FATTR's varying cache, 2^n words
  def texDescCacheLog2: Int = 2
  def maxTrianglesPerTile: Int = 256    // at least SEQ_MAX_TRI in software/borg/borg_layout.h
  // The three edges and Zn; the far plane's sample deltas are Zn's, negated.
  def coveragePlanesStored: Int = 4
  def tileDepthBits: Int = 32           // holds every D16/D24 value and D32_SFLOAT exactly
  def pcBits: Int = 14                  // branches reach their 1024-word page, JAL anywhere
  // The largest power of two within IMEM; lines at and above it are never replaced.
  def icacheLinesLog2: Int = chisel3.util.log2Floor(maxInstructions)
  // Line state in a RAM instead of flops once flops would cost more than a RAM.
  def icacheMetaInRam: Boolean = maxInstructions > 128
  def fp: FloatConfig = FloatConfig.FP32 // Vulkan's minimum
  def totalBits: Int = fp.totalBits
  def fragLanes: Int = 4                // a 2x2 quad: DDX/DDY need it
  def exp: Int = fp.exp
  def sig: Int = fp.sig

  // Pipeline stages (BorgCore's "Pipeline" doc), counted down to write-back at 1.
  def cPipeEn2: Int = 3                 // magR capture
  // All three operands valid; BorgCore also starts TEX/TEXA, LOAD/STORE and branches here.
  def cOperands: Int = 4
  def cHoldC: Int = 5                   // issue: rs1, rs2 and rs3 read
  def cBusyLoad: Int = cHoldC
  def busyCounterWidth: Int = chisel3.util.log2Ceil(cBusyLoad + 1)
}

object BorgConfig {
  // What Vulkan 1.0 needs: the simulators, and the base of every other config.
  val Default = BorgConfig()

  // The ULX3S: what unmodified cube.c uses, since the full set does not fit the ECP5-85K.
  // 1024 bin tiles cover its 128x128 framebuffer.
  val Cube = Default.copy(maxBinTiles = 1024, samples = 1, hasBlend = false,
                          hasStencil = false, hasCompute = false, hasDepthFlush = false)

  // The ASIC (BorgOnlyTop, QspiSocTop too): Default sized down; cube.frag is 59 words.
  val Wafer = Default.copy(
    coordWidth       = 7,
    maxBinTiles      = 16,
    maxInstructions  = 64,
    maxUniforms      = 32
  )

  // Unit tests: the tile dirty bits dominate elaboration time.
  val Test = Default.copy(maxBinTiles = 64)
}
