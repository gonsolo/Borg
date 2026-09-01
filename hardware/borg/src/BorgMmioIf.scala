// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import hutt.HuttBus

/** Common interface shared by [[Borg]]'s IO and [[borg.link.BorgLinkMaster]]'s IO:
  * the CPU-facing MMIO port and the GPU memory port.
  *
  * This is the property the whole wafer.space bridge plan rests on:
  * `Peripherals` can select between a local `Borg` and the FPGA-side link
  * adapter behind one `borgIf` handle, and the rest of the SoC -- decode,
  * arbitration, `wireGpuMem()`, HDMI scanout, firmware, `borgvk` -- cannot tell
  * which is underneath.
  */
trait BorgMmioIf {
  def mmio: HuttBus
  def gpuMem: GpuMemIO
}
