// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// Rewrite for 16-bit-word MemBackendIO © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package memory

import chisel3._
import chisel3.util._
import hutt.{HuttBus, HuttInstrBus, HuttSize}
import borg.GpuMemIO

/** Memory controller IO; a [[QspiBackend]] or [[SdramBackend]] connects to `backend`. */
class MemoryControllerIO extends Bundle {
  val instr   = Flipped(new HuttInstrBus(23))
  val cpuData = Flipped(new HuttBus(25))
  val gpuMem  = Flipped(new GpuMemIO)
  val backend = new MemBackendIO

  val debug_state = Output(UInt(3.W))

  // The two halfword-read captures, to tell which backend transaction carried a bad word.
  val debug_hw0 = Output(UInt(16.W))
  val debug_hw1 = Output(UInt(16.W))
}

/** Arbitrates instruction fetch, CPU data and the GPU port over one [[MemBackendIO]], splitting
  * each access into one or two halfword transactions.
  *
  * @param gpuVramRegionBit force byte-address bit 24 onto GPU accesses so they hit the bytes the
  *   CPU sees: QSPI's PSRAM select, or ULX3S's 0x1000000 SDRAM window. False for harnesses whose
  *   CPU path adds no such bit.
  */
class MemoryController(gpuVramRegionBit: Boolean = true) extends Module {
  val io = IO(new MemoryControllerIO)

  val sIdle :: sIssue :: sWait :: sRespond :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val rInstr :: rCpuRead :: rCpuWrite :: rGpuRead :: rGpuWrite :: Nil = Enum(5)
  val rKind = RegInit(rInstr)

  val hwIdx   = RegInit(0.U(1.W))
  val needTwo = RegInit(false.B)

  // GPU write burst of `lenReg` words; the backend pulses `accept` per word.
  val burst   = RegInit(false.B)
  val lenReg  = RegInit(1.U(7.W))

  val reqByteAddr = RegInit(0.U(25.W))
  val reqData     = RegInit(0.U(32.W))
  val reqSize     = RegInit(HuttSize.Word)
  val hw0Reg      = RegInit(0.U(16.W))
  val hw1Reg      = RegInit(0.U(16.W))

  // Byte writes are read-merge-write: dqm-less backends write the whole 16-bit lane.
  val rmwBg          = RegInit(0.U(16.W))
  val rmwReadPending = RegInit(false.B)

  val VRAM_REGION_BIT = "h1000000".U(25.W)

  val baseWordAddr = reqByteAddr(24, 1)
  val currentWordAddr = baseWordAddr + hwIdx

  val currentWriteHw = Mux(hwIdx === 0.U, reqData(15, 0), reqData(31, 16))

  val currentByteEn = "b11".U(2.W)

  val byteWriteHw = Mux(
    reqByteAddr(0),
    Cat(reqData(7, 0), rmwBg(7, 0)),
    Cat(rmwBg(15, 8), reqData(7, 0))
  )
  val halfwordWriteHw = reqData(15, 0)
  val wordWriteHw = currentWriteHw

  val txWriteHw = MuxLookup(reqSize, wordWriteHw)(Seq(
    HuttSize.Byte -> byteWriteHw,
    HuttSize.Half -> halfwordWriteHw,
    HuttSize.Word -> wordWriteHw
  ))

  val isReadReq = (rKind === rInstr) || (rKind === rCpuRead) || (rKind === rGpuRead)

  // A byte write's first backend transaction is its RMW read.
  val doingRmwRead = (rKind === rCpuWrite) && rmwReadPending
  val txIsRead     = isReadReq || doingRmwRead

  io.backend.addrIn     := currentWordAddr
  io.backend.dataIn     := Mux(burst, io.gpuMem.wdata(15, 0), txWriteHw)
  io.backend.byteEnIn   := currentByteEn
  io.backend.lenIn      := Mux(burst, lenReg, 1.U)
  io.backend.startRead  := (state === sIssue) &&  txIsRead
  io.backend.startWrite := (state === sIssue) && !txIsRead

  // Zero-extended; the CPU sign-extends.
  val cpuRespBits = MuxLookup(reqSize, Cat(hw1Reg, hw0Reg))(Seq(
    HuttSize.Byte -> Cat(0.U(24.W), Mux(reqByteAddr(0), hw0Reg(15, 8), hw0Reg(7, 0))),
    HuttSize.Half -> Cat(0.U(16.W), hw0Reg),
    HuttSize.Word -> Cat(hw1Reg, hw0Reg)
  ))

  val gpuRespBits = Cat(hw1Reg, hw0Reg)
  val instrRespBits = Cat(hw1Reg, hw0Reg)

  // Fetch has the lowest priority: ready only when nothing else competes, or the fetch is dropped.
  io.instr.req.ready   := (state === sIdle) &&
                          !io.cpuData.req.valid && !io.gpuMem.wr && !io.gpuMem.req
  io.instr.resp.valid  := (state === sRespond) && (rKind === rInstr)
  io.instr.resp.bits   := instrRespBits

  io.cpuData.req.ready := (state === sIdle)
  io.cpuData.resp.valid := (state === sRespond) && (rKind === rCpuRead || rKind === rCpuWrite)
  io.cpuData.resp.bits  := cpuRespBits

  io.gpuMem.data  := gpuRespBits
  io.gpuMem.ready := (state === sRespond) && (rKind === rGpuRead || rKind === rGpuWrite)
  io.gpuMem.waccept := burst && io.backend.accept

  io.debug_state := state
  io.debug_hw0   := hw0Reg
  io.debug_hw1   := hw1Reg

  switch(state) {
    is(sIdle) {
      rmwReadPending := false.B
      burst          := false.B
      // Priority: CPU read > CPU write > GPU write > GPU read > instr fetch.
      when(io.cpuData.req.valid && !io.cpuData.req.bits.write) {
        rKind       := rCpuRead
        reqByteAddr := io.cpuData.req.bits.addr
        reqSize     := io.cpuData.req.bits.size
        needTwo     := io.cpuData.req.bits.size === HuttSize.Word
        hwIdx       := 0.U
        state       := sIssue
      }.elsewhen(io.cpuData.req.valid && io.cpuData.req.bits.write) {
        rKind       := rCpuWrite
        reqByteAddr := io.cpuData.req.bits.addr
        reqData     := io.cpuData.req.bits.data
        reqSize     := io.cpuData.req.bits.size
        needTwo     := io.cpuData.req.bits.size === HuttSize.Word
        rmwReadPending := io.cpuData.req.bits.size === HuttSize.Byte
        hwIdx       := 0.U
        state       := sIssue
      }.elsewhen(io.gpuMem.wr) {
        rKind       := rGpuWrite
        // 16 MiB of VRAM: only the low 24 address bits decode.
        reqByteAddr := (if (gpuVramRegionBit) io.gpuMem.addr(23, 0) | VRAM_REGION_BIT
                         else io.gpuMem.addr(23, 0))
        reqData     := io.gpuMem.wdata
        // GPU writes carry 16 bits per word.
        reqSize     := HuttSize.Half
        needTwo     := false.B
        hwIdx       := 0.U
        burst       := io.gpuMem.wlen > 1.U
        lenReg      := io.gpuMem.wlen
        state       := sIssue
      }.elsewhen(io.gpuMem.req) {
        rKind       := rGpuRead
        reqByteAddr := (if (gpuVramRegionBit) io.gpuMem.addr(23, 0) | VRAM_REGION_BIT
                         else io.gpuMem.addr(23, 0))
        reqSize     := HuttSize.Word
        needTwo     := true.B
        hwIdx       := 0.U
        state       := sIssue
      }.elsewhen(io.instr.req.valid) {
        rKind       := rInstr
        reqByteAddr := Cat(io.instr.req.bits, 0.U(2.W))
        reqSize     := HuttSize.Word
        needTwo     := true.B
        hwIdx       := 0.U
        state       := sIssue
      }
    }

    is(sIssue) {
      state := sWait
    }

    is(sWait) {
      when(io.backend.done) {
        when(doingRmwRead) {
          // RMW read done: re-issue as the merged write.
          rmwBg          := io.backend.dataOut
          rmwReadPending := false.B
          state          := sIssue
        }.otherwise {
          when(isReadReq) {
            when(hwIdx === 0.U) { hw0Reg := io.backend.dataOut }
              .otherwise        { hw1Reg := io.backend.dataOut }
          }
          when(needTwo && hwIdx === 0.U) {
            hwIdx := 1.U
            state := sIssue
          }.otherwise {
            state := sRespond
          }
        }
      }
    }

    is(sRespond) {
      // The GPU's `ready` is a one-cycle pulse with no handshake.
      when((rKind === rInstr   && io.instr.resp.fire) ||
           (rKind === rCpuRead && io.cpuData.resp.fire) ||
           (rKind === rCpuWrite && io.cpuData.resp.fire) ||
           (rKind === rGpuRead) ||
           (rKind === rGpuWrite)) {
        state := sIdle
      }
    }
  }
}
