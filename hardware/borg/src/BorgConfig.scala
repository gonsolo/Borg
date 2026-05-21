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
  // ONE configuration for sim, ULX3S, and ASIC — they are bit-for-bit identical
  // so the simulator faithfully models hardware.  pico-ice is retired (the full
  // GPU no longer fits the iCE40), so the per-target variants (PicoIce/ULX3S)
  // are gone.  A divergent ULX3S config (hasImemMmio=false) once shipped a
  // HW-only bug: the firmware loads FPU instructions/shaders into IMEM via MMIO
  // (borg_fpu.c), so hasImemMmio MUST be true and the sim must exercise it.
  // (The size/isLarge machinery below is now always Large — vestigial pico-ice
  // generality, slated for removal in a follow-up.)
  val Default = BorgConfig(
    fp          = FloatConfig.FP16,
    size        = Large,
    coordWidth  = 9,   // max 512×512 framebuffer (covers 500×500)
    fifoDepth   = 2,
    hasImemMmio = true
  )
}
