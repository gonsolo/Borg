// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import borg.{Borg, BorgConfig, GpuMemIO}

/** Borg behind [[HostLink]], as on a board: the harness (`simulation/direct`, `link_sim`) feeds the
  * protocol's bytes and answers `gpu` from a flat byte array.
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

  val gpu_addr    = IO(Output(UInt(GpuMemIO.AddrBits.W)))
  val gpu_req     = IO(Output(Bool()))
  val gpu_data    = IO(Input(UInt(32.W)))
  val gpu_ready   = IO(Input(Bool()))
  val gpu_wr      = IO(Output(Bool()))
  val gpu_wdata   = IO(Output(UInt(32.W)))
  val gpu_wlen    = IO(Output(UInt(7.W)))
  val gpu_waccept = IO(Input(Bool()))

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

    gpu_addr  := arb.io.out.addr
    gpu_req   := arb.io.out.req
    gpu_wr    := arb.io.out.wr
    gpu_wdata := arb.io.out.wdata
    gpu_wlen  := arb.io.out.wlen
    arb.io.out.data    := gpu_data
    arb.io.out.ready   := gpu_ready
    arb.io.out.waccept := gpu_waccept
  }
}

/** Emit FIRRTL for [[BorgLinkSimTop]] (arcilator input). */
object BorgLinkSimMain extends App {
  Emit.emitFIRRTL(new BorgLinkSimTop(BorgConfig.Default), "out/hardware/borg/firrtl_link")
}
