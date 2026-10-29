// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._
import borg.GpuMemIO
import hutt.HuttBus

class BorgLinkSlaveIO(val p: LinkParams) extends Bundle {

  // To Borg's Flipped mmio port: the adapter drives `req`.
  val mmio = new HuttBus(10)

  val gpuMem = Flipped(new GpuMemIO)

  val dnPins = Input(new LinkPins(p.w))
  val upPins = Output(new LinkPins(p.w))

  val dnCred = Output(Bool())   // returned for consumed M.A packets
  val upCred = Input(Bool())    // returned by the far side for our V.A packets

  val linkFast = Input(Bool())
  // `link_narrow` strap: halve the lanes at runtime (narrowCapable builds only).
  val narrow   = Input(Bool())
  val linkUp   = Output(Bool())
  val linkErr  = Output(Bool())

  // For a chip that does not come up; see BorgLinkClockGenIO's dbg fields.
  val dbgTrainGood = Output(UInt(log2Ceil(p.trainBeats + 1).W))
  val dbgChanged   = Output(Bool())
  val dbgRxErr     = Output(Bool())
  val dbgRxParity  = Output(Bool())
  val dbgTxBusy    = Output(Bool())
}

/** ASIC-side link adapter between the pins and Borg's ports. Only the flit stream crosses:
  * `gpuMem.ready` and `waccept` are generated locally, and a burst is drained into `vBuf` before
  * it is sent, since a [[LinkTx]] packet cannot stall once started. */
class BorgLinkSlave(val p: LinkParams) extends Module {
  val io = IO(new BorgLinkSlaveIO(p))

  val clkgen = Module(new BorgLinkClockGen(p, isMaster = false, dnPinsPreRegistered = true))
  val tx     = Module(new LinkTx(p))
  val rx     = Module(new LinkRx(p, isDn = true))

  // Falling-edge pre-register: the far side launches on the same clock, so a rising-edge capture
  // behind the clock tree fails hold. d/v/p share one delay: rx compares them every beat, and
  // clkgen's phase lock reads d.
  private val fallClock = (!clock.asBool).asClock
  private val dnPinsIn  = withClock(fallClock)(RegNext(io.dnPins))

  clkgen.io.linkFast  := io.linkFast
  clkgen.io.narrow    := io.narrow
  clkgen.io.farLinkUp := false.B
  clkgen.io.rxPins    := dnPinsIn

  val beatEn = clkgen.io.beatEn
  val linkUp = clkgen.io.linkUp
  io.linkUp := linkUp

  tx.io.beatEn := beatEn
  tx.io.narrow := io.narrow
  rx.io.beatEn := beatEn
  rx.io.narrow := io.narrow
  rx.io.pins   := dnPinsIn
  io.upPins    := tx.io.pins

  // Received traffic counts only from the first parity-valid idle beat after link_up: training
  // carries v=1 with valid parity and decodes as packet headers, so where it stops can leave the
  // receiver mid-packet. LinkRx aborts that packet on this beat, before `synced` is set.
  // BorgLinkMaster sends the idle beat by holding its link_up until a gap has gone out.
  val synced = RegInit(false.B)
  when(linkUp && rx.io.idle) { synced := true.B }

  val errSticky = RegInit(false.B)
  when(rx.io.err && linkUp && synced) { errSticky := true.B }
  io.linkErr := errSticky

  io.dbgTrainGood := clkgen.io.dbgTrainGood
  io.dbgChanged   := clkgen.io.dbgChanged
  io.dbgRxErr     := rx.io.err
  io.dbgRxParity  := rx.io.errParity
  io.dbgTxBusy    := tx.io.busy

  val rxFire  = rx.io.out.valid && linkUp && synced
  val rxHdr   = rx.io.hdr
  val rxIsM   = rxHdr.chan === LinkChan.M // M.A request from the FPGA
  val rxIsV   = rxHdr.chan === LinkChan.V // V.D response to one of our reads
  val rxFirst = rx.io.out.bits.first
  val rxFlit  = rx.io.out.bits.flit
  val rxIdx   = rx.io.out.bits.idx

  // MMIO path: M.A in on DN, drive Borg, M.D out on UP.
  val sMIdle :: sMCollect :: sMReq :: sMResp :: sMSend :: Nil = Enum(5)
  val mState = RegInit(sMIdle)

  val mAddr  = Reg(UInt(10.W))
  val mSize  = Reg(UInt(2.W))
  val mWrite = Reg(Bool())
  val mData  = Reg(UInt(32.W))
  val mResp  = Reg(UInt(32.W))
  val mFlit  = RegInit(0.U(2.W))

  io.mmio.req.valid      := mState === sMReq
  io.mmio.req.bits.addr  := mAddr
  io.mmio.req.bits.data  := mData
  io.mmio.req.bits.write := mWrite
  io.mmio.req.bits.size  := mSize
  // resp.valid can be withdrawn mid-flight (autoRunStall); with ready held high only a fire counts.
  io.mmio.resp.ready     := mState === sMResp

  val mCredRet = RegInit(false.B)
  io.dnCred := mCredRet

  switch(mState) {
    is(sMIdle) {
      when(rxFire && rxIsM && rxFirst) {
        mAddr  := LinkHeader.mmioAddr(rxHdr)
        mSize  := LinkHeader.mmioSize(rxHdr)
        mWrite := rxHdr.opcode =/= TLOpcode.Get
        mFlit  := 0.U
        when(rxHdr.opcode === TLOpcode.Get) {
          mState := sMReq
        }.otherwise {
          mState := sMCollect
        }
      }
    }
    is(sMCollect) {
      // An aborted packet never delivers its data flits; waiting here would
      // take the next request's header as payload.
      when(rx.io.err) { mState := sMIdle }
      when(rxFire) {
        when(rxIdx === 1.U) { mData := Cat(mData(31, 16), rxFlit) }
          .otherwise {
            mData  := Cat(rxFlit, mData(15, 0))
            mState := sMReq
          }
      }
    }
    is(sMReq) {
      when(io.mmio.req.fire) { mState := sMResp }
    }
    is(sMResp) {
      when(io.mmio.resp.fire) {
        mResp  := io.mmio.resp.bits
        mFlit  := 0.U
        mState := sMSend
      }
    }
    is(sMSend) { /* advanced by the transmit handshake below */ }
  }

  // M.D packet: AccessAck (1 flit) for writes, AccessAckData (3) for reads.
  val mSendLen = Mux(mWrite, 1.U, 3.U)
  val mHdr = LinkHeader(
    LinkChan.M,
    Mux(mWrite, TLOpcode.AccessAck, TLOpcode.AccessAckData),
    0.U
  ).asUInt

  tx.io.d.valid     := mState === sMSend
  tx.io.d.bits.flit := MuxLookup(mFlit, mHdr)(
    Seq(0.U -> mHdr, 1.U -> mResp(15, 0), 2.U -> mResp(31, 16))
  )
  tx.io.d.bits.last := mFlit === (mSendLen - 1.U)

  when(mState === sMSend && tx.io.d.fire) {
    when(tx.io.d.bits.last) {
      mState := sMIdle
      // The adapter holds one request, so the credit returns only once it retires.
      mCredRet := !mCredRet
    }.otherwise {
      mFlit := mFlit + 1.U
    }
  }

  // gpuMem path: Borg's requests out as V.A on UP, V.D responses back on DN.
  val sVIdle :: sVDrain :: sVSend :: sVWait :: Nil = Enum(4)
  val vState = RegInit(sVIdle)

  val vAddr     = Reg(UInt(GpuMemIO.AddrBits.W))
  val vWrite    = Reg(Bool())
  val vWlenLog2 = Reg(UInt(3.W))
  val vBuf      = Reg(Vec(p.maxBurst, UInt(16.W)))
  val vCnt      = RegInit(0.U(log2Ceil(p.maxBurst + 1).W))
  val vFlit     = RegInit(0.U(log2Ceil(p.maxPacketFlits + 1).W))
  val vData     = Reg(UInt(32.W))
  val vReady    = RegInit(false.B)

  val vWords = (1.U << vWlenLog2).asUInt

  io.gpuMem.ready := vReady
  io.gpuMem.data  := vData
  // Bursts only, as MemoryController: Borg routes `waccept` to the flusher unconditionally, so a
  // pulse during another unit's single-word write would advance the flusher's burst pointer.
  io.gpuMem.waccept := (vState === sVDrain) && (vWords > 1.U)

  // No falling-edge stage: CreditCounter already synchronizes upCred.
  val credit = Module(new CreditCounter(p.creditDepth))
  credit.io.returnPin := io.upCred

  vReady := false.B

  switch(vState) {
    is(sVIdle) {
      // Masters hold req/wr through the ready cycle, so skip that cycle as MemoryController does,
      // or the finished request is issued twice. Before sync no V.D could ever answer a request.
      when(!vReady && linkUp && synced) {
        // Writes before reads, as MemoryController.
        when(io.gpuMem.wr) {
          vAddr     := io.gpuMem.addr
          vWrite    := true.B
          vWlenLog2 := Log2(io.gpuMem.wlen)
          vCnt      := 0.U
          vState    := sVDrain
        }.elsewhen(io.gpuMem.req) {
          vAddr     := io.gpuMem.addr
          vWrite    := false.B
          // The header still carries the length field.
          vWlenLog2 := 0.U
          vFlit     := 0.U
          vState    := sVSend
        }
      }
    }
    is(sVDrain) {
      // One 16-bit word per flit; vCnt counts to maxBurst inclusive, so truncate the index.
      vBuf(vCnt(log2Ceil(p.maxBurst) - 1, 0)) := io.gpuMem.wdata(15, 0)
      vCnt       := vCnt + 1.U
      when(vCnt === (vWords - 1.U)) {
        vFlit  := 0.U
        vState := sVSend
      }
    }
    is(sVSend) { /* advanced by the transmit handshake below */ }
    is(sVWait) {
      when(rxFire && rxIsV) {
        when(rxHdr.opcode === TLOpcode.AccessAckData) {
          when(rx.io.out.bits.idx === 1.U) { vData := Cat(vData(31, 16), rxFlit) }
            .elsewhen(rx.io.out.bits.idx === 2.U) {
              vData  := Cat(rxFlit, vData(15, 0))
              vReady := true.B
              vState := sVIdle
            }
        }.otherwise {
          // AccessAck
          vReady := true.B
          vState := sVIdle
        }
      }
    }
  }

  val vSendLen = Mux(vWrite, 3.U +& vWords, 3.U)
  val vHdr = LinkHeader(
    LinkChan.V,
    Mux(vWrite, TLOpcode.PutFullData, TLOpcode.Get),
    LinkHeader.vramPayload(vWlenLog2)
  ).asUInt

  val vBufIdx = (vFlit - 3.U)(log2Ceil(p.maxBurst) - 1, 0)
  tx.io.a.valid := (vState === sVSend) && credit.io.available
  tx.io.a.bits.flit := MuxCase(
    vBuf(vBufIdx),
    Seq(
      (vFlit === 0.U) -> vHdr,
      (vFlit === 1.U) -> vAddr(15, 0),
      (vFlit === 2.U) -> vAddr(31, 16)
    )
  )
  tx.io.a.bits.last := vFlit === (vSendLen - 1.U)

  credit.io.consume := tx.io.a.fire && tx.io.a.bits.last

  when(vState === sVSend && tx.io.a.fire) {
    when(tx.io.a.bits.last) {
      vFlit  := 0.U
      vState := sVWait
    }.otherwise {
      vFlit := vFlit + 1.U
    }
  }

  assert(
    !(vState === sVIdle && !vReady && io.gpuMem.wr && !isPow2Wlen(io.gpuMem.wlen)),
    "BorgLinkSlave: gpuMem burst length must be a power of two -- the wire format " +
      "encodes it as log2 so that packet length stays a pure function of the header"
  )

  private def isPow2Wlen(wlen: UInt): Bool = (wlen & (wlen - 1.U)) === 0.U && wlen =/= 0.U
}
