// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Test-only wrapper: a word-addressed MMIO surface over [[Borg]]'s Decoupled bus.
  *
  *  - Writes: the data_write_n 3 -> 2 edge issues one request, same cycle.
  *  - Reads: level-triggered; while data_read_n = 2, re-issue whenever nothing is in flight,
  *    so a test can poll a status register.
  *  - data_out passes the response through combinationally, else holds the last value.
  *  - data_ready: no read pending, or a read response has arrived.
  */
class BorgTestWrapperIO(val cfg: BorgConfig) extends Bundle {
  val address        = Input(UInt(10.W))
  val data_in        = Input(UInt(32.W))
  val data_write_n   = Input(UInt(2.W))
  val data_read_n    = Input(UInt(2.W))
  val data_out       = Output(UInt(32.W))
  val data_ready     = Output(Bool())
  val uo_out         = Output(UInt(8.W))
  val user_interrupt = Output(Bool())
  val gpuMem         = new GpuMemIO
}

/** Anything presenting this MMIO surface, so one test runs both directly and over the
  * link in `borg.link` and compares the output.
  */
trait HasLegacyBorgMmio {
  def io: BorgTestWrapperIO
}

class BorgTestWrapper(val cfg: BorgConfig = BorgConfig.Default) extends Module with HasLegacyBorgMmio {
  val io = IO(new BorgTestWrapperIO(cfg))

  val borg = Module(new Borg(cfg))

  io.uo_out         := 0.U
  io.user_interrupt := false.B
  borg.io.gpuMem    <> io.gpuMem

  val writeNDel  = RegNext(io.data_write_n, 3.U(2.W))
  val writeEdge  = (io.data_write_n === 2.U) && (writeNDel =/= 2.U)
  val readActive = io.data_read_n === 2.U

  // `pendingReq` only matters if Borg back-pressures req.ready.
  val inflightReq    = RegInit(false.B)
  val inflightIsRead = RegInit(false.B)

  val pendingReq    = RegInit(false.B)
  val pendingAddr   = Reg(UInt(10.W))
  val pendingData   = Reg(UInt(32.W))
  val pendingIsRead = Reg(Bool())

  val canIssueNew = !pendingReq && !inflightReq
  val newWriteReq = writeEdge  && canIssueNew
  val newReadReq  = readActive && canIssueNew
  val newReq      = newWriteReq || newReadReq

  val effectiveAddr   = Mux(pendingReq, pendingAddr,   io.address)
  val effectiveData   = Mux(pendingReq, pendingData,   io.data_in)
  val effectiveIsRead = Mux(pendingReq, pendingIsRead, newReadReq)

  borg.io.mmio.req.valid      := newReq || pendingReq
  borg.io.mmio.req.bits.addr  := effectiveAddr
  borg.io.mmio.req.bits.data  := effectiveData
  borg.io.mmio.req.bits.write := !effectiveIsRead
  borg.io.mmio.req.bits.size  := 2.U
  borg.io.mmio.resp.ready     := true.B

  when(newReq && !borg.io.mmio.req.fire) {
    pendingReq    := true.B
    pendingAddr   := io.address
    pendingData   := io.data_in
    pendingIsRead := newReadReq
  }
  when(borg.io.mmio.req.fire) {
    pendingReq     := false.B
    inflightReq    := true.B
    inflightIsRead := !borg.io.mmio.req.bits.write
  }
  when(borg.io.mmio.resp.fire) {
    inflightReq := false.B
  }

  val dataOutReg = RegInit(0.U(32.W))
  when(borg.io.mmio.resp.fire && inflightIsRead) {
    dataOutReg := borg.io.mmio.resp.bits
  }

  // Pass through on resp.fire so a peek right after step(1) sees the fresh value.
  io.data_out := Mux(
    borg.io.mmio.resp.valid && inflightIsRead,
    borg.io.mmio.resp.bits,
    dataOutReg
  )

  // Sticky until read_n deasserts.
  val readGotData = RegInit(false.B)
  when(borg.io.mmio.resp.fire && inflightIsRead) { readGotData := true.B }
  when(!readActive) { readGotData := false.B }

  io.data_ready := (!readActive) || readGotData ||
                    (borg.io.mmio.resp.valid && inflightIsRead)
}
