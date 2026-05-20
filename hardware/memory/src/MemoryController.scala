// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// Rewrite for 16-bit-word MemBackendIO © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package memory

import chisel3._
import chisel3.util._
import hutt.{HuttBus, HuttInstrBus, HuttSize}
import borg.GpuMemIO

/** IO bundle for the SoC-level memory controller.
  *
  * Arbitrates the memory backend bus between four requestors:
  *   - CPU instruction fetch ([[HuttInstrBus]], single 32-bit word per request)
  *   - CPU data read/write ([[HuttBus]], byte/halfword/word access)
  *   - GPU write (autonomous tile flush)
  *   - GPU read (scanout / texel fetch)
  *
  * Physical memory pins are NOT owned here; connect a [[QspiBackend]] or
  * [[SdramBackend]] to io.backend at the top level.
  */
class MemoryControllerIO extends Bundle {
  val instr   = Flipped(new HuttInstrBus(23))
  val cpuData = Flipped(new HuttBus(25))
  val gpuMem  = Flipped(new GpuMemIO)
  val backend = new MemBackendIO

  val debug_state = Output(UInt(3.W))
}

/** SoC-level memory controller — backend-agnostic arbiter.
  *
  * Each CPU/GPU access is decomposed into one or two halfword transactions on
  * the [[MemBackendIO]] interface:
  *   - Byte/halfword: 1 halfword transaction.
  *   - Word: 2 back-to-back halfword transactions.
  *
  * Byte-write granularity uses byteEnIn to mask the inactive lane. (Note: the
  * current SdramController ignores dqm during normal R/W — byte writes will
  * clobber the adjacent byte until SdramController gets a dqm input.)
  */
class MemoryController extends Module {
  val io = IO(new MemoryControllerIO)

  // ── FSM ────────────────────────────────────────────────────────────────────
  // sIdle    — accepting new requests
  // sIssue   — pulse startRead/startWrite for one cycle
  // sWait    — wait for backend.done; capture read data
  // sRespond — deliver result to requester; advance to next halfword if
  //            needTwo and we've only done halfword 0, else back to sIdle.
  val sIdle :: sIssue :: sWait :: sRespond :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // Requester kind
  val rInstr :: rCpuRead :: rCpuWrite :: rGpuRead :: rGpuWrite :: Nil = Enum(5)
  val rKind = RegInit(rInstr)

  // Halfword tracking
  val hwIdx   = RegInit(0.U(1.W))  // 0 or 1 within current CPU/GPU access
  val needTwo = RegInit(false.B)   // true when the request needs 2 halfwords

  // Latched request fields
  val reqByteAddr = RegInit(0.U(25.W))
  val reqData     = RegInit(0.U(32.W))
  val reqSize     = RegInit(HuttSize.Word)
  val hw0Reg      = RegInit(0.U(16.W))  // first halfword read back
  val hw1Reg      = RegInit(0.U(16.W))  // second halfword read back

  // PSRAM-region select: byte-address bit 24 (= QspiController addr_in(24)).
  // gpuMem (GPU PSRAM port) addresses are PSRAM SPI-relative and must carry
  // this bit so they alias the CPU's PSRAM_OUT_RAW accesses.
  val VRAM_REGION_BIT = "h1000000".U(25.W)

  // ── Convenience signals ────────────────────────────────────────────────────
  // Word address (16-bit-word, 24 bits) for the SDRAM transaction.
  val baseWordAddr = reqByteAddr(24, 1)
  val currentWordAddr = baseWordAddr + hwIdx

  // For writes: which halfword of reqData are we sending now.
  val currentWriteHw = Mux(hwIdx === 0.U, reqData(15, 0), reqData(31, 16))

  // byteEn for the current halfword. For full halfword/word writes this is
  // "11". For byte writes (size = byte), only one lane is active, chosen by
  // bit 0 of the byte address.
  val currentByteEn = Mux(
    reqSize === HuttSize.Byte,
    Mux(reqByteAddr(0), "b10".U(2.W), "b01".U(2.W)),
    "b11".U(2.W)
  )

  // For byte writes we replicate the byte into both lanes so the active lane
  // (per byteEn) has the correct value. Even-addressed byte → low lane;
  // odd-addressed byte → high lane.
  val byteWriteHw = Cat(reqData(7, 0), reqData(7, 0))
  val halfwordWriteHw = reqData(15, 0)
  val wordWriteHw = currentWriteHw   // depends on hwIdx

  val txWriteHw = MuxLookup(reqSize, wordWriteHw)(Seq(
    HuttSize.Byte -> byteWriteHw,
    HuttSize.Half -> halfwordWriteHw,
    HuttSize.Word -> wordWriteHw
  ))

  val isReadReq = (rKind === rInstr) || (rKind === rCpuRead) || (rKind === rGpuRead)

  // ── Backend wiring ─────────────────────────────────────────────────────────
  io.backend.addrIn     := currentWordAddr
  io.backend.dataIn     := txWriteHw
  io.backend.byteEnIn   := currentByteEn
  io.backend.startRead  := (state === sIssue) &&  isReadReq
  io.backend.startWrite := (state === sIssue) && !isReadReq

  // ── Response presentation ──────────────────────────────────────────────────
  // For lb/lh/lw: assemble the result. For byte/halfword reads, only hw0 is
  // valid; the top 16 bits of the resp are zero (CPU sign-extends from byte
  // or halfword fields). For word reads, hw1:hw0.
  val cpuRespBits = MuxLookup(reqSize, Cat(hw1Reg, hw0Reg))(Seq(
    HuttSize.Byte -> Cat(0.U(24.W), Mux(reqByteAddr(0), hw0Reg(15, 8), hw0Reg(7, 0))),
    HuttSize.Half -> Cat(0.U(16.W), hw0Reg),
    HuttSize.Word -> Cat(hw1Reg, hw0Reg)
  ))

  val gpuRespBits = Cat(hw1Reg, hw0Reg)
  val instrRespBits = Cat(hw1Reg, hw0Reg)

  // ── Default IO ─────────────────────────────────────────────────────────────
  // Instruction fetch is the LOWEST-priority requester (see sIdle arbitration).
  // req.ready must therefore only assert when the controller will actually
  // accept the fetch this cycle — i.e. no higher-priority requester (CPU data
  // or GPU) is competing.  Otherwise the CPU's instr.req "fires" while the
  // controller services the GPU instead, dropping the fetch and wedging the CPU
  // in sFetchResp forever (manifests once the GPU is active).
  io.instr.req.ready   := (state === sIdle) &&
                          !io.cpuData.req.valid && !io.gpuMem.wr && !io.gpuMem.req
  io.instr.resp.valid  := (state === sRespond) && (rKind === rInstr)
  io.instr.resp.bits   := instrRespBits

  io.cpuData.req.ready := (state === sIdle)
  io.cpuData.resp.valid := (state === sRespond) && (rKind === rCpuRead || rKind === rCpuWrite)
  io.cpuData.resp.bits  := cpuRespBits

  io.gpuMem.data  := gpuRespBits
  io.gpuMem.ready := (state === sRespond) && (rKind === rGpuRead || rKind === rGpuWrite)

  io.debug_state := state

  // ── FSM ────────────────────────────────────────────────────────────────────
  switch(state) {
    is(sIdle) {
      // Priority: CPU read > CPU write > GPU write > GPU read > instr fetch.
      // Each branch latches the request and goes to sIssue.
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
        hwIdx       := 0.U
        state       := sIssue
      }.elsewhen(io.gpuMem.wr) {
        rKind       := rGpuWrite
        // gpuMem is the GPU's PSRAM port: its addresses are PSRAM SPI-relative
        // byte addresses (e.g. flush_fb_base, seq_*_addr).  The CPU reaches the
        // same data via PSRAM_OUT_RAW, which adds the PSRAM base so byte bit 24
        // is set.  Force bit 24 here so GPU and CPU accesses hit identical
        // backend words (otherwise the GPU reads/writes the flash region).
        reqByteAddr := io.gpuMem.addr | VRAM_REGION_BIT
        reqData     := io.gpuMem.wdata
        reqSize     := HuttSize.Word
        needTwo     := true.B
        hwIdx       := 0.U
        state       := sIssue
      }.elsewhen(io.gpuMem.req) {
        rKind       := rGpuRead
        reqByteAddr := io.gpuMem.addr | VRAM_REGION_BIT
        reqSize     := HuttSize.Word
        needTwo     := true.B
        hwIdx       := 0.U
        state       := sIssue
      }.elsewhen(io.instr.req.valid) {
        rKind       := rInstr
        // instr.req.bits is a 23-bit word address. Convert to 25-bit byte addr.
        reqByteAddr := Cat(io.instr.req.bits, 0.U(2.W))
        reqSize     := HuttSize.Word
        needTwo     := true.B
        hwIdx       := 0.U
        state       := sIssue
      }
    }

    is(sIssue) {
      // startRead/startWrite is pulsed via the wiring above. Move to wait.
      state := sWait
    }

    is(sWait) {
      when(io.backend.done) {
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

    is(sRespond) {
      // Wait for the matching ready/fire. For the GPU's `ready` we just pulse
      // it for one cycle and assume the GPU latches synchronously.
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
