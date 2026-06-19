// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** Bridges a 64-bit (RV64) Hutt data bus to a 32-bit memory/peripheral fabric.
  *
  * The Borg SoC fabric — MemoryController, SDRAM backend, Borg MMIO, SoC inline
  * registers — is natively 32-bit and stays that way.  This adapter lets an
  * RV64 Hutt drive it: byte/half/word accesses (size 0..2) pass straight through
  * on the low 32 bits; a doubleword access (size 3, from LD/SD) is split into two
  * 32-bit transactions — low word at `addr`, high word at `addr+4`.
  *
  * Load contract is preserved: the 32-bit fabric returns the addressed bytes in
  * the low bits, and the CPU's `extendLoad` sign/zero-extends.  For a doubleword
  * load the adapter concatenates {high, low} into the 64-bit response; for ≤word
  * loads the high half is zero (the CPU ignores it).
  *
  * RV32 builds do not instantiate this — the 32-bit core wires straight to the
  * fabric — so the existing SoC/demo path is unaffected.
  */
class HuttDataWidthAdapterIO(val addrWidth: Int) extends Bundle {
  val cpu = Flipped(new HuttBus(addrWidth, 64))  // 64-bit CPU side
  val mem = new HuttBus(addrWidth, 32)           // 32-bit fabric side
}

class HuttDataWidthAdapter(val addrWidth: Int) extends Module {
  val io = IO(new HuttDataWidthAdapterIO(addrWidth))

  val sIdle :: sReq0 :: sResp0 :: sReq1 :: sResp1 :: sDone :: Nil = Enum(6)
  val state = RegInit(sIdle)

  val reqAddr  = Reg(UInt(addrWidth.W))
  val reqWrite = Reg(Bool())
  val reqSize  = Reg(UInt(2.W))
  val reqData  = Reg(UInt(64.W))
  val loadLo   = Reg(UInt(32.W))
  val loadHi   = Reg(UInt(32.W))

  val isDouble = reqSize === HuttSize.Double   // size 3 = LD/SD

  // -- defaults --
  io.cpu.req.ready  := state === sIdle
  io.cpu.resp.valid := state === sDone
  io.cpu.resp.bits  := Mux(isDouble, Cat(loadHi, loadLo), Cat(0.U(32.W), loadLo))

  io.mem.req.valid      := false.B
  io.mem.req.bits.addr  := reqAddr
  io.mem.req.bits.write := reqWrite
  // Doubleword beats are word-sized; ≤word accesses keep their real size.
  io.mem.req.bits.size  := Mux(isDouble, HuttSize.Word, reqSize)
  io.mem.req.bits.data  := reqData(31, 0)
  io.mem.resp.ready     := false.B

  switch(state) {
    is(sIdle) {
      when(io.cpu.req.fire) {
        reqAddr  := io.cpu.req.bits.addr
        reqWrite := io.cpu.req.bits.write
        reqSize  := io.cpu.req.bits.size
        reqData  := io.cpu.req.bits.data
        state    := sReq0
      }
    }
    is(sReq0) {
      io.mem.req.valid     := true.B
      io.mem.req.bits.data := reqData(31, 0)
      when(io.mem.req.fire) { state := sResp0 }
    }
    is(sResp0) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.fire) {
        loadLo := io.mem.resp.bits
        state  := Mux(isDouble, sReq1, sDone)
      }
    }
    is(sReq1) {
      io.mem.req.valid     := true.B
      io.mem.req.bits.addr := reqAddr + 4.U
      io.mem.req.bits.data := reqData(63, 32)
      when(io.mem.req.fire) { state := sResp1 }
    }
    is(sResp1) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.fire) {
        loadHi := io.mem.resp.bits
        state  := sDone
      }
    }
    is(sDone) {
      when(io.cpu.resp.fire) { state := sIdle }
    }
  }
}
