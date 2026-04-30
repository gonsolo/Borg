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
  * @param fifoDepth    Command FIFO depth. 1 saves ~12 LCs on iCE40.
  * @param hasImemMmio  When true, the MMIO IMEM/uniform write paths are synthesised.
  *                     Set false on FPGA once firmware uses DMA (Step 22.0, ~30 LCs saved).
  *                     Must remain true in Sim so Chisel tests can still poke IMEM directly.
  * @param hasFlusher   When true, BorgTileFlusher is instantiated and wired into the
  *                     GpuMemIO mux.  Set false on FPGA until Step 25.4 adds real PSRAM
  *                     writes (the scaffold + mux costs ~32 LCs that Yosys cannot optimize
  *                     away through the FSM register).  Always true in Sim for test coverage.
  */
case class BorgConfig(
    fp: FloatConfig = FloatConfig.FP16,
    coordWidth: Int = 9,
    fifoDepth: Int = 2,
    hasImemMmio: Boolean = true,
    hasDMA: Boolean = true,
    hasFlusher: Boolean = true
) {
  def totalBits: Int = fp.totalBits
  def exp: Int = fp.exp
  def sig: Int = fp.sig
}

object BorgConfig {
  /** pico-ice iCE40 UP5K: 5280 LCs, 30 BRAMs, 4 MHz.
    * hasImemMmio stays true until firmware DMA is verified (Step 22.4),
    * then flip to false for ~30 LC savings (Step 22.0).
    */
  val FPGA = BorgConfig(
    fp           = FloatConfig.FP16,
    coordWidth   = 6,   // max 64×64 framebuffer
    fifoDepth    = 1,
    hasImemMmio  = true,  // reverted: hasDMA=false requires MMIO for shader loading
    hasDMA       = false, // ~327 LCs synthesised (2026-04-29); retry after LC budget cleared
    hasFlusher   = false  // costs +222 LCs at nextpnr (230 over budget); ULX3S milestone
  )

  /** Verilator / Arcilator simulation: no area constraint. */
  val Sim = BorgConfig(
    fp           = FloatConfig.FP16,
    coordWidth   = 9,   // max 512×512 framebuffer (covers 500×500)
    fifoDepth    = 2,
    hasImemMmio  = true // always true in sim: tests poke IMEM via MMIO
  )
}
