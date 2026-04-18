// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Build-time configuration for the Borg GPU.
  *
  * Encapsulates all target-specific knobs so that a single parameter
  * flows through the entire hierarchy.
  *
  * @param fp         Floating-point format (FP16 for iCE40, FP32 for future ASIC)
  * @param coordWidth Pixel coordinate width: max framebuffer dimension = 2^coordWidth.
  *                   6 → 64px (FPGA), 9 → 512px (ASIC/sim for 500×500 renders)
  * @param fifoDepth  Command FIFO depth. 1 saves ~12 LCs on iCE40.
  */
case class BorgConfig(
    fp: FloatConfig = FloatConfig.FP16,
    coordWidth: Int = 9,
    fifoDepth: Int = 2
) {
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig
}

object BorgConfig {
  /** pico-ice iCE40 UP5K: 5280 LCs, 30 BRAMs, 4 MHz. */
  val FPGA = BorgConfig(
    fp         = FloatConfig.FP16,
    coordWidth = 6,   // max 64×64 framebuffer
    fifoDepth  = 2
  )

  /** Verilator / Arcilator simulation: no area constraint. */
  val Sim = BorgConfig(
    fp         = FloatConfig.FP16,
    coordWidth = 9,   // max 512×512 framebuffer (covers 500×500)
    fifoDepth  = 2
  )
}
