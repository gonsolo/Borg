// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._


/** DMA descriptor latched from MMIO at trigger time. */
class DMADescriptor extends Bundle {
  val baseAddr = UInt(25.W) // GPU memory byte address (PSRAM/SDRAM, 4-byte aligned); 25b = 32 MB
  val length   = UInt(7.W)  // number of 32-bit PSRAM words to transfer (1–72)
  val dest     = UInt(2.W)  // 0=IMEM, 1=Uniform-page0, 2=Uniform-page1
  val offset   = UInt(7.W)  // starting word index in the destination buffer (IMEM up to 72)
}

class BorgDMAIO extends Bundle {
  val start        = Input(Bool())
  val desc         = Input(new DMADescriptor)
  // Which uniform page (0/1) a dest=1 uniform DMA writes to.  Driven by the
  // sequencer's active page so the 2-entry setup cache can fill either page.
  val uniformWritePage = Input(UInt(1.W))
  val busy         = Output(Bool())
  val gpuMem      = new GpuMemIO
  val imemWrite    = new MemWritePort(7, 32)
  val uniformWrite = new MemWritePort(6, 16)
  // 32-bit snoop port for sequencer (valid when gpuMem.ready)
  val snoop        = Output(Valid(UInt(32.W)))
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

  val addrReg  = RegInit(0.U(25.W))
  val countReg = RegInit(0.U(6.W))
  // descReg removed (Step 26.3): io.desc fields are wired directly in sRead;
  // firmware must hold them stable from start until busy deasserts.

  // Defaults
  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U   // DMA only reads

  io.snoop.valid  := false.B
  io.snoop.bits   := 0.U

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
        // Explicitly truncate to 6 bits — Chisel's + on two 6-bit UInts
        // infers a 7-bit result; the top bit is never used and causes
        // a Verilator UNUSEDSIGNAL warning.
        val destIdx = (io.desc.offset + countReg)(6, 0)

        io.snoop.valid := true.B
        io.snoop.bits  := io.gpuMem.data

        when(io.desc.dest === 0.U) {
          // IMEM: full 32-bit word
          io.imemWrite.en   := true.B
          io.imemWrite.addr := destIdx
          io.imemWrite.data := io.gpuMem.data
          if (BorgDebug.trace) printf("[DMA] imemWrite[%d]=0x%x\n", destIdx, io.gpuMem.data)
        }.elsewhen(io.desc.dest === 1.U) {
          // Uniform buffer: low 16 bits; page selected by the sequencer (2-entry
          // setup cache) rather than hardcoded — was always page 0 before.
          val page = io.uniformWritePage
          io.uniformWrite.en   := true.B
          io.uniformWrite.addr := Cat(page, destIdx(4, 0))
          io.uniformWrite.data := io.gpuMem.data(15, 0)
          when(destIdx < 2.U || destIdx === 19.U || destIdx === 22.U || destIdx === 25.U) {
            if (BorgDebug.trace) printf("[DMA] uniWrite idx=%d data=0x%x (raw32=0x%x)\n",
              destIdx, io.gpuMem.data(15, 0), io.gpuMem.data)
          }
        }.elsewhen(io.desc.dest === 2.U) {
          // Snoop only (no write to IMEM or Uniforms)
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
