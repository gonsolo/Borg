// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// End-to-end test: GPU write → MemoryController → SDRAM → GPU read.
//
// Verifies the full MemoryController data path for GPU operations
// using SdramBackendSim (behavioral SDRAM model).
//
// This is the exact path the scanout uses — if data round-trips correctly
// here, the scanout will display correctly (already proven with hardcoded values).

package memory

import chisel3._
import chisel3.util._

// ── Test harness: MemoryController + SdramBackendSim ──
// Exposes the gpuMem port directly for testbench control.

class GpuMemTestHarnessIO extends Bundle {
  // GPU memory port (directly exposed)
  val gpuReq   = Input(Bool())
  val gpuWr    = Input(Bool())
  val gpuAddr  = Input(UInt(25.W))
  val gpuWdata = Input(UInt(32.W))
  val gpuReady = Output(Bool())
  val gpuData  = Output(UInt(32.W))

  // Debug
  val memBusy      = Output(Bool())
  val beState      = Output(UInt(4.W))
  val ctrlState    = Output(UInt(3.W))
}

class GpuMemTestHarness extends Module {
  val io = IO(new GpuMemTestHarnessIO)

  val mem   = Module(new MemoryController)
  val sdram = Module(new SdramBackendSim(
    words   = 16384,  // 32 KB — enough for 64×64×2 = 8 KB framebuffer
    rdDelay = 4,
    wrDelay = 2
  ))

  // ── Wire MemoryController ↔ SdramBackendSim ──
  sdram.io.backend <> mem.io.backend

  // ── Tie off CPU ports (no CPU in this test) ──
  mem.io.instrFetch.instr_addr          := 0.U
  mem.io.instrFetch.instr_fetch_restart := false.B
  mem.io.instrFetch.instr_fetch_stall   := false.B
  mem.io.cpuData.addr         := 0.U
  mem.io.cpuData.dataOut      := 0.U
  mem.io.cpuData.writeN       := 3.U  // no CPU write
  mem.io.cpuData.readN        := 3.U  // no CPU read
  mem.io.cpuData.dataContinue := false.B

  // ── GPU memory port — directly exposed ──
  mem.io.gpuMem.req   := io.gpuReq
  mem.io.gpuMem.wr    := io.gpuWr
  mem.io.gpuMem.addr  := io.gpuAddr
  mem.io.gpuMem.wdata := io.gpuWdata
  io.gpuReady         := mem.io.gpuMem.ready
  io.gpuData          := mem.io.gpuMem.data

  // ── Debug ──
  io.memBusy   := sdram.io.backend.busy
  io.beState   := sdram.io.debug_be_state
  io.ctrlState := sdram.io.debug_ctrl_state
}
