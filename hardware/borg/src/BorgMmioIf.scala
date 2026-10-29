// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import hutt.HuttBus

/** The MMIO and GPU memory ports shared by [[Borg]] and
  * [[borg.link.BorgLinkMaster]], so the SoC cannot tell a local Borg from the link. */
trait BorgMmioIf {
  def mmio: HuttBus
  def gpuMem: GpuMemIO
}
