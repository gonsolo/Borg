// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._


/** DMA descriptor latched from MMIO at trigger time. */
class DMADescriptor extends Bundle {
  val baseAddr = UInt(20.W) // PSRAM byte address (must be 4-byte aligned)
  val length   = UInt(6.W)  // number of 32-bit PSRAM words to transfer (1–56)
  val dest     = UInt(2.W)  // 0=IMEM, 1=Uniform-page0, 2=Uniform-page1
  val offset   = UInt(6.W)  // starting word index in the destination buffer
}

class BorgDMAIO extends Bundle {
  val start        = Input(Bool())
  val desc         = Input(new DMADescriptor)
  val busy         = Output(Bool())
  val gpuMem      = new GpuMemIO
  val imemWrite    = new MemWritePort(6, 32)
  val uniformWrite = new MemWritePort(6, 16)
}

/** BorgDMA — bulk PSRAM→IMEM/Uniform DMA engine (Step 22.1).
  *
  * Drives the same [[GpuMemIO]] port used by sTexFetch. Arbitration is
  * handled externally in Borg.scala; DMA only runs when the rasterizer is
  * idle so the two never contend in practice.
  *
  * FSM:
  *   sIdle → sRead on start pulse
  *   sRead: assert gpuMem.req; on ready → write word + advance counter;
  *          if all words done → sIdle, else stay in sRead
  *
  * dest encoding:
  *   0 = IMEM        (32-bit words, direct)
  *   1 = Uniform page 0 (low 16 bits of each PSRAM word)
  *   2 = Uniform page 1 (low 16 bits of each PSRAM word)
  *
  * Protocol: io.desc fields (baseAddr, length, dest, offset) must remain
  * stable from the start pulse until busy deasserts. They are driven
  * directly as combinational wires in sRead — no local descReg copy is
  * needed, saving ~34 LCs (Step 26.3).
  */
class BorgDMA extends Module {
  val io = IO(new BorgDMAIO)

  val sIdle :: sRead :: Nil = Enum(2)
  val state = RegInit(sIdle)

  val addrReg  = RegInit(0.U(20.W))
  val countReg = RegInit(0.U(6.W))
  // descReg removed (Step 26.3): io.desc fields are wired directly in sRead;
  // firmware must hold them stable from start until busy deasserts.

  // Defaults
  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U

  io.imemWrite.en   := false.B
  io.imemWrite.addr := 0.U
  io.imemWrite.data := 0.U

  io.uniformWrite.en   := false.B
  io.uniformWrite.addr := 0.U
  io.uniformWrite.data := 0.U

  io.busy := state =/= sIdle

  switch(state) {
    is(sIdle) {
      when(io.start) {
        addrReg  := io.desc.baseAddr
        countReg := 0.U
        state    := sRead
      }
    }

    is(sRead) {
      io.gpuMem.req  := true.B
      io.gpuMem.addr := addrReg

      when(io.gpuMem.ready) {
        val destIdx = io.desc.offset +& countReg

        when(io.desc.dest === 0.U) {
          // IMEM: full 32-bit word
          io.imemWrite.en   := true.B
          io.imemWrite.addr := destIdx
          io.imemWrite.data := io.gpuMem.data
        }.otherwise {
          // Uniform buffer: low 16 bits; page from dest field
          val page = Mux(io.desc.dest === 2.U, 1.U(1.W), 0.U(1.W))
          io.uniformWrite.en   := true.B
          io.uniformWrite.addr := Cat(page, destIdx(4, 0))
          io.uniformWrite.data := io.gpuMem.data(15, 0)
        }

        addrReg  := addrReg + 4.U
        countReg := countReg + 1.U
        when(countReg + 1.U >= io.desc.length) {
          state := sIdle
        }
        // otherwise: stay in sRead — next cycle re-asserts req with new addr
      }
    }
  }
}
