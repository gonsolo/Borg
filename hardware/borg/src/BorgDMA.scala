// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class DMADescriptor extends Bundle {
  val baseAddr = UInt(GpuMemIO.AddrBits.W) // 4-byte aligned
  val length   = UInt(7.W)  // 32-bit words
  val dest     = UInt(2.W)  // 0 = IMEM, 1 = uniforms (page uniformWritePage), 2 = snoop only
  val offset   = UInt(7.W)  // first word in the destination
}

class BorgDMAIO(val cfg: BorgConfig) extends Bundle {
  val start        = Input(Bool())
  val desc         = Input(new DMADescriptor)
  val uniformWritePage = Input(UInt(1.W))
  val busy         = Output(Bool())
  val gpuMem      = new GpuMemIO
  val imemWrite    = new MemWritePort(7, 32)
  val uniformWrite = new MemWritePort(6, cfg.totalBits)
  val snoop        = Output(Valid(UInt(32.W)))
}

/** Bulk DRAM to IMEM or uniform DMA. `desc` is not latched: it must stay
  * stable from start until busy drops. */
class BorgDMA(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgDMAIO(cfg))

  val sIdle :: sRead :: Nil = Enum(2)
  val state = RegInit(sIdle)

  val addrReg  = RegInit(0.U(GpuMemIO.AddrBits.W))
  val countReg = RegInit(0.U(6.W))

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

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
        val destIdx = (io.desc.offset + countReg)(6, 0)

        io.snoop.valid := true.B
        io.snoop.bits  := io.gpuMem.data

        when(io.desc.dest === 0.U) {
          io.imemWrite.en   := true.B
          io.imemWrite.addr := destIdx
          io.imemWrite.data := io.gpuMem.data
          if (BorgDebug.trace) printf("[DMA] imemWrite[%d]=0x%x\n", destIdx, io.gpuMem.data)
        }.elsewhen(io.desc.dest === 1.U) {
          // A uniform slot holds the word's low cfg.totalBits bits.
          val page = io.uniformWritePage
          io.uniformWrite.en   := true.B
          io.uniformWrite.addr := Cat(page, destIdx(4, 0))
          io.uniformWrite.data := io.gpuMem.data(cfg.totalBits - 1, 0)
          when(destIdx < 2.U || destIdx === 19.U || destIdx === 22.U || destIdx === 25.U) {
            if (BorgDebug.trace) printf("[DMA] uniWrite idx=%d data=0x%x (raw32=0x%x)\n",
              destIdx, io.gpuMem.data(cfg.totalBits - 1, 0), io.gpuMem.data)
          }
        }.elsewhen(io.desc.dest === 2.U) {
          // Snoop only.
        }

        addrReg  := addrReg + 4.U
        countReg := countReg + 1.U
        when(countReg + 1.U >= io.desc.length) {
          state := sIdle
        }
      }
    }
  }
}
