// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Target size for the Borg GPU.
  *
  * `Small` targets area-constrained FPGAs (pico-ice iCE40 UP5K, 5280 LCs).
  * Only the core rendering pipeline is synthesised; no DMA, flusher,
  * sequencer, or binner.
  *
  * `Large` targets roomy FPGAs (ULX3S ECP5-85K, 84,480 LUT4s) and
  * simulation/ASIC.  All hardware features are enabled.
  */
sealed trait BorgSize
case object Small extends BorgSize
case object Large extends BorgSize

/** Build-time configuration for the Borg GPU.
  *
  * Encapsulates all target-specific knobs so that a single parameter
  * flows through the entire hierarchy.
  *
  * @param fp           Floating-point format (FP16 for iCE40, FP32 for future ASIC)
  * @param size         `Small` (pico-ice) or `Large` (ULX3S / Sim / ASIC).
  *                     Controls whether DMA, flusher, sequencer, and binner
  *                     are synthesised.
  * @param coordWidth   Pixel coordinate width: max framebuffer dimension = 2^coordWidth.
  *                     6 → 64px (FPGA), 9 → 512px (ASIC/sim for 500×500 renders)
  * @param fifoDepth    Command FIFO depth. 1 saves ~12 LCs on iCE40.
  * @param hasImemMmio  When true, the MMIO IMEM/uniform write paths are synthesised.
  *                     Set false on FPGA once firmware uses DMA (Step 22.0, ~30 LCs saved).
  *                     Must remain true in Sim so Chisel tests can still poke IMEM directly.
  */
case class BorgConfig(
    fp: FloatConfig = FloatConfig.FP16,
    size: BorgSize = Large,
    coordWidth: Int = 9,
    fifoDepth: Int = 2,
    hasImemMmio: Boolean = true
) {
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig

  /** True when all extended hardware is present (DMA, flusher, sequencer, binner). */
  def isLarge: Boolean = size == Large
}

object BorgConfig {
  /** pico-ice iCE40 UP5K: 5280 LCs, 30 BRAMs, 4 MHz.
    * Small size: no DMA, flusher, sequencer, or binner.
    */
  val PicoIce = BorgConfig(
    fp          = FloatConfig.FP16,
    size        = Small,
    coordWidth  = 6,   // max 64×64 framebuffer
    fifoDepth   = 1,
    hasImemMmio = true  // required: hasDMA=false, so MMIO needed for shader loading
  )

  /** ULX3S ECP5-85K: 84,480 LUT4s, all features enabled.
    * Large size: DMA, flusher, sequencer, and binner all synthesised.
    */
  val ULX3S = BorgConfig(
    fp          = FloatConfig.FP16,
    size        = Large,
    coordWidth  = 9,   // max 512×512 framebuffer
    fifoDepth   = 2,
    hasImemMmio = false // DMA used for shader loading on ECP5
  )

  /** Verilator / Arcilator simulation: no area constraint. */
  val Sim = BorgConfig(
    fp          = FloatConfig.FP16,
    size        = Large,
    coordWidth  = 9,   // max 512×512 framebuffer (covers 500×500)
    fifoDepth   = 2,
    hasImemMmio = true  // always true in sim: tests poke IMEM via MMIO
  )
}
