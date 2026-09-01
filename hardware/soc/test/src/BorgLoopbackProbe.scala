// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import borg.BorgConfig
import borg.link.LinkParams

/** Test-only harness exercising exactly the new plumbing rung A adds:
  * `Peripherals`'s `borgOpt`/`linkOpt` selection and `SoCLogic.wireBorgLoopback()`.
  *
  * Deliberately skips `wireSoC()` -- the CPU-side address demux is Hutt/decode
  * logic already covered by [[SoCRoutingTests]], and pulling in a real `Hutt`
  * plus `MemoryController` plus a running firmware image just to poke Borg's
  * MMIO surface would test that machinery again, not this. Instead `peripherals`
  * and `mem` are driven/observed directly, exactly the ports `wireSoC()` would
  * connect the CPU/SDRAM to in a real target -- so this is a fair substitute for
  * "the CPU issues an MMIO access", not a shortcut around what matters.
  *
  * @param borgMode BorgDirect for the reference DUT, BorgLoopback for the one
  *                 under test in [[PeripheralsLoopbackEquivalenceTests]].
  */
class BorgLoopbackProbeIO extends Bundle {
  val mmio   = Flipped(new hutt.HuttBus(12))
  val gpuMem = new borg.GpuMemIO
}

class BorgLoopbackProbe(val cfg: BorgConfig, mode: BorgMode) extends RawModule with SoCLogic {
  def CLOCK_MHZ: Int = 25
  override def BORG_CFG: BorgConfig = cfg
  override def borgMode: BorgMode = mode
  override def linkParams: LinkParams = LinkParams(trainBeats = 8)
  override def hasClint: Boolean = false
  override def hasSupervisorMode: Boolean = false
  override def hasDebugPorts: Boolean = false

  val clk   = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))
  val io    = IO(new BorgLoopbackProbeIO)

  def soc_clk: Clock = clk
  def soc_rst_n: Bool = rst_n
  lazy val soc_rst_reg_n: Bool = withClockAndReset(clk, false.B) { RegNext(rst_n, false.B) }
  def soc_ui_in: UInt = 0.U(8.W)

  peripherals.io.mmio <> io.mmio
  peripherals.io.gpuMem <> io.gpuMem
  peripherals.io.ui_in := 0.U

  if (mode == BorgLoopback) wireBorgLoopback()
}

/** `chisel3.simulator`'s `simulate()` drives an implicit `clock`/`reset`, which
  * a `RawModule` doesn't have -- [[BorgLoopbackProbe]] takes explicit `clk`/
  * `rst_n` ports instead, matching every real board top in this repo
  * (`tt_um_gonsolo_borg`, `ulx3s_top`, ...). This thin `Module` wrapper
  * supplies that implicit clock/reset and forwards it in, which is the
  * standard way to simulate a `RawModule` child.
  */
class BorgLoopbackProbeHarness(val cfg: BorgConfig, mode: BorgMode) extends Module {
  val io = IO(new BorgLoopbackProbeIO)

  val dut = Module(new BorgLoopbackProbe(cfg, mode))
  dut.clk   := clock
  dut.rst_n := !reset.asBool
  dut.io <> io
}
