// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._
import borg.{Borg, BorgConfig, BorgTestWrapperIO, HasLegacyBorgMmio}

/** A real [[Borg]] behind the full chip-to-chip link, presenting the *same*
  * legacy MMIO surface as `BorgTestWrapper`.
  *
  * This is the equivalence gate's other half: identical stimulus can be driven
  * against `BorgTestWrapper` (direct) and this (over the bridge), and the
  * resulting `gpuMem` write streams compared bit for bit.
  *
  * The one semantic difference from the direct wrapper is `data_ready`, which
  * here also goes low while a *write* is in flight.  The direct wrapper completes
  * writes same-cycle so it never needs to report that, but over a ~30-cycle link a
  * scenario driver must wait.  Polling `data_ready` is therefore a no-op against
  * the direct wrapper and correct against this one -- which is what lets a single
  * driver serve both.
  */
class BorgLinkTestWrapper(val cfg: BorgConfig, val p: LinkParams)
    extends Module
    with HasLegacyBorgMmio {

  val io = IO(new BorgTestWrapperIO(cfg))

  val master = Module(new BorgLinkMaster(p))
  val slave  = Module(new BorgLinkSlave(p))
  val borg   = Module(new Borg(cfg))

  // -- Pins ------------------------------------------------------------------
  slave.io.dnPins     := master.io.dnPins
  master.io.upPins    := slave.io.upPins
  master.io.dnCred    := slave.io.dnCred
  slave.io.upCred     := master.io.upCred
  master.io.farLinkUp := slave.io.linkUp
  master.io.linkFast  := false.B
  slave.io.linkFast   := false.B

  // -- Far side: the link's slave drives the real Borg ------------------------
  borg.io.mmio   <> slave.io.mmio
  slave.io.gpuMem <> borg.io.gpuMem

  // -- Near side: the link's master replays gpuMem to the test ---------------
  master.io.gpuMem <> io.gpuMem

  // Borg's own uo_out/user_interrupt were dead (tied to constants) and have
  // been removed from BorgIO entirely -- see BorgTestWrapper's matching fix.
  io.uo_out         := 0.U
  io.user_interrupt := false.B
  io.covDeltaDebug.foreach(_ := borg.io.covDeltaDebug.get)

  // -- Legacy MMIO translation -----------------------------------------------
  // Same edge/level semantics as BorgTestWrapper, but a request stays in flight
  // for a link round trip rather than completing combinationally.
  val writeNDel  = RegNext(io.data_write_n, 3.U(2.W))
  val writeEdge  = (io.data_write_n === 2.U) && (writeNDel =/= 2.U)
  val readActive = io.data_read_n === 2.U

  val inflight       = RegInit(false.B)
  val inflightIsRead = RegInit(false.B)
  val readGotData    = RegInit(false.B)

  val canIssue = !inflight && master.io.linkUp
  // Unlike the direct wrapper, do not re-issue a read once its data has arrived:
  // over a link that would launch a fresh round trip on every held-high cycle.
  val newRead  = readActive && !readGotData && canIssue
  val newWrite = writeEdge && canIssue

  master.io.mmio.req.valid      := newRead || newWrite
  master.io.mmio.req.bits.addr  := io.address
  master.io.mmio.req.bits.data  := io.data_in
  master.io.mmio.req.bits.write := newWrite && !newRead
  master.io.mmio.req.bits.size  := 2.U
  master.io.mmio.resp.ready     := true.B

  when(master.io.mmio.req.fire) {
    inflight       := true.B
    inflightIsRead := !master.io.mmio.req.bits.write
  }
  when(master.io.mmio.resp.fire) {
    inflight := false.B
    when(inflightIsRead) { readGotData := true.B }
  }
  when(!readActive) { readGotData := false.B }

  val dataOutReg = RegInit(0.U(32.W))
  when(master.io.mmio.resp.fire && inflightIsRead) {
    dataOutReg := master.io.mmio.resp.bits
  }
  io.data_out := Mux(
    master.io.mmio.resp.valid && inflightIsRead,
    master.io.mmio.resp.bits,
    dataOutReg
  )

  io.data_ready := master.io.linkUp && !inflight &&
    ((!readActive) || readGotData)
}
