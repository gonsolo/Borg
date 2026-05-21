// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Test-only wrapper that exposes a word-addressed MMIO surface
  * on top of [[Borg]] whose production IO is [[hutt.HuttBus]]
  * Decoupled req/resp.  Lets the existing 130+ test pokes keep working
  * unchanged while the production Borg presents a clean Decoupled bus.
  *
  * == Protocol translation ==
  *
  *  - Writes: rising edge from data_write_n=3 → data_write_n=2 issues one
  *    Decoupled write request, combinationally same-cycle so the test's
  *    immediate step(1) sees the write completed.
  *
  *  - Reads: level-triggered.  As long as data_read_n=2, keep re-issuing
  *    read requests whenever no transaction is currently in flight.  This
  *    matches the original level-sensitive `bus.is_reading = data_read_n === 2`
  *    semantics where the CPU could poll a status register by sustaining the
  *    read for many cycles.
  *
  *  - data_out: combinational pass-through of borg.resp.bits when a read
  *    response is valid; otherwise holds the last captured value.  This lets
  *    `peek()` immediately after step(1) observe the fresh read value.
  *
  *  - data_ready: 1 when no read is pending (data_read_n=3) OR when a read
  *    response has completed in the current/previous cycle.
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

class BorgTestWrapper(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  def this(fp: FloatConfig) = this(BorgConfig.Default.copy(fp = fp))

  val io = IO(new BorgTestWrapperIO(cfg))

  val borg = Module(new Borg(cfg))

  io.uo_out         := borg.io.uo_out
  io.user_interrupt := borg.io.user_interrupt
  borg.io.gpuMem    <> io.gpuMem

  // -- Edge / level detection -----------------------------------------------
  val writeNDel  = RegNext(io.data_write_n, 3.U(2.W))
  val writeEdge  = (io.data_write_n === 2.U) && (writeNDel =/= 2.U)
  val readActive = io.data_read_n === 2.U

  // -- In-flight / pending state --------------------------------------------
  // `inflightReq` covers the window between a request firing and its response
  // firing.  `pendingReq` only matters if Borg back-pressures req.ready.
  val inflightReq    = RegInit(false.B)
  val inflightIsRead = RegInit(false.B)

  val pendingReq    = RegInit(false.B)
  val pendingAddr   = Reg(UInt(10.W))
  val pendingData   = Reg(UInt(32.W))
  val pendingIsRead = Reg(Bool())

  // Issue a new request when:
  //   - a write edge fires, or read level is high, AND
  //   - no request is already pending or in flight.
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

  // Latch into pendingReq only if Borg refuses to accept.
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

  // -- Read data path -------------------------------------------------------
  // Hold the most recent read value so `data_out` stays valid between reads.
  val dataOutReg = RegInit(0.U(32.W))
  when(borg.io.mmio.resp.fire && inflightIsRead) {
    dataOutReg := borg.io.mmio.resp.bits
  }

  // Combinational pass-through during the resp.fire cycle so the test's
  // `peek()` right after step(1) sees the fresh value, not the latched one.
  io.data_out := Mux(
    borg.io.mmio.resp.valid && inflightIsRead,
    borg.io.mmio.resp.bits,
    dataOutReg
  )

  // Track whether a read response has completed (sticky until read_n
  // deasserts) so the legacy data_ready signal matches the old contract.
  val readGotData = RegInit(false.B)
  when(borg.io.mmio.resp.fire && inflightIsRead) { readGotData := true.B }
  when(!readActive) { readGotData := false.B }

  io.data_ready := (!readActive) || readGotData ||
                    (borg.io.mmio.resp.valid && inflightIsRead)
}
