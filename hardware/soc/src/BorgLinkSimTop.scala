// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import borg.{Borg, BorgConfig}

/** The board's data path: Borg behind [[HostLink]], memory through [[GpuMemMig]]. The harness
  * (`simulation/direct`, `link_sim`) feeds the protocol's bytes and plays the MIG.
  */
class BorgLinkSimTop(cfg: BorgConfig) extends RawModule {
  val clk   = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))

  val in_valid  = IO(Input(Bool()))
  val in_ready  = IO(Output(Bool()))
  val in_data   = IO(Input(UInt(8.W)))
  val out_valid = IO(Output(Bool()))
  val out_ready = IO(Input(Bool()))
  val out_data  = IO(Output(UInt(8.W)))

  val mig = IO(new MigUi)

  withClockAndReset(clk, !rst_n) {
    val b    = Module(new Borg(cfg))
    val link = Module(new HostLink)
    val arb  = Module(new GpuMemArbiter(2))
    link.io.mmio <> b.io.mmio
    arb.io.in(0) <> b.io.gpuMem
    arb.io.in(1) <> link.io.mem

    link.io.in.valid  := in_valid
    link.io.in.bits   := in_data
    in_ready          := link.io.in.ready
    out_valid         := link.io.out.valid
    out_data          := link.io.out.bits
    link.io.out.ready := out_ready

    val ddr = Module(new GpuMemMig)
    ddr.io.gpu <> arb.io.out
    ddr.io.uiClock := clk
    ddr.io.uiReset := !rst_n
    mig <> ddr.io.mig
  }
}

/** Emit FIRRTL for [[BorgLinkSimTop]] (arcilator input). */
object BorgLinkSimMain extends App {
  Emit.emitFIRRTL(new BorgLinkSimTop(BorgConfig.Default), "out/hardware/borg/firrtl_link")
}
