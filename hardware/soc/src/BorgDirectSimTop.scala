// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import borg.{Borg, BorgConfig, GpuMemIO}

/** Borg alone, for the direct simulator (`simulation/direct`).
  *
  * No CPU, no UART, no memory controller: the C++ harness is the host.  It
  * drives the register bus (`mmio`) like a driver would and answers the GPU's
  * memory port (`gpuMem`) from a flat byte array, so the simulated hardware is
  * exactly the `Borg` module that sits behind the CPU on the SoC targets.
  *
  * Config follows `BORG_SIM_CFG` (default `simt`), the same as the SoC sims, so
  * the goldens made through the firmware path stay comparable.
  *
  * Emit FIRRTL: `mill hardware.soc.runMain soc.BorgDirectSimMain`
  */
class BorgDirectSimTop(cfg: BorgConfig) extends RawModule {
  val clk   = IO(Input(Clock()))
  val rst_n = IO(Input(Bool()))

  // Register bus (HuttBus(10) flattened).  Single outstanding transaction.
  val mmio_req_valid  = IO(Input(Bool()))
  val mmio_req_ready  = IO(Output(Bool()))
  val mmio_req_addr   = IO(Input(UInt(10.W)))
  val mmio_req_write  = IO(Input(Bool()))
  val mmio_req_size   = IO(Input(UInt(2.W)))
  val mmio_req_data   = IO(Input(UInt(32.W)))
  val mmio_resp_valid = IO(Output(Bool()))
  val mmio_resp_ready = IO(Input(Bool()))
  val mmio_resp_data  = IO(Output(UInt(32.W)))

  // GPU memory port (GpuMemIO flattened; the harness is the slave).
  val gpu_addr    = IO(Output(UInt(GpuMemIO.AddrBits.W)))
  val gpu_req     = IO(Output(Bool()))
  val gpu_data    = IO(Input(UInt(32.W)))
  val gpu_ready   = IO(Input(Bool()))
  val gpu_wr      = IO(Output(Bool()))
  val gpu_wdata   = IO(Output(UInt(32.W)))
  val gpu_wlen    = IO(Output(UInt(7.W)))
  val gpu_waccept = IO(Input(Bool()))

  val b = withClockAndReset(clk, !rst_n) { Module(new Borg(cfg)) }

  b.io.mmio.req.valid       := mmio_req_valid
  mmio_req_ready            := b.io.mmio.req.ready
  b.io.mmio.req.bits.addr   := mmio_req_addr
  b.io.mmio.req.bits.write  := mmio_req_write
  b.io.mmio.req.bits.size   := mmio_req_size
  b.io.mmio.req.bits.data   := mmio_req_data
  mmio_resp_valid           := b.io.mmio.resp.valid
  b.io.mmio.resp.ready      := mmio_resp_ready
  mmio_resp_data            := b.io.mmio.resp.bits

  gpu_addr  := b.io.gpuMem.addr
  gpu_req   := b.io.gpuMem.req
  b.io.gpuMem.data    := gpu_data
  b.io.gpuMem.ready   := gpu_ready
  gpu_wr    := b.io.gpuMem.wr
  gpu_wdata := b.io.gpuMem.wdata
  gpu_wlen  := b.io.gpuMem.wlen
  b.io.gpuMem.waccept := gpu_waccept
}
