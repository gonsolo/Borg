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
  * @param hasImemMmio  When true, the MMIO IMEM/uniform write paths are synthesised.
  *                     Must remain true so firmware can load FPU instructions/shaders
  *                     into IMEM via MMIO (borg_fpu.c).
  */
case class BorgConfig(
    fp: FloatConfig = FloatConfig.FP16,
    coordWidth: Int = 9,
    fifoDepth: Int = 2,
    hasImemMmio: Boolean = true
) {
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig
}

object BorgConfig {
  // ONE configuration for sim, ULX3S, and ASIC — they are bit-for-bit identical
  // so the simulator faithfully models hardware.  pico-ice is retired (the full
  // GPU no longer fits the iCE40), so the per-target variants (PicoIce/ULX3S)
  // are gone.  A divergent ULX3S config (hasImemMmio=false) once shipped a
  // HW-only bug: the firmware loads FPU instructions/shaders into IMEM via MMIO
  // (borg_fpu.c), so hasImemMmio MUST be true and the sim must exercise it.
  val Default = BorgConfig(
    fp          = FloatConfig.FP16,
    coordWidth  = 9,   // max 512×512 framebuffer (covers 500×500)
    fifoDepth   = 2,
    hasImemMmio = true
  )
}
