// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._
import borg.GpuMemIO
import hutt.HuttBus

class BorgLinkSlaveIO(val p: LinkParams) extends Bundle {

  /** To Borg's `mmio` port.  Borg declares `Flipped(HuttBus(10))`, so the adapter
    * takes the unflipped side: it drives `req` and receives `resp`.
    */
  val mmio = new HuttBus(10)

  /** To Borg's `gpuMem` port.  Borg is the master, so the adapter is the memory side. */
  val gpuMem = Flipped(new GpuMemIO)

  val dnPins = Input(new LinkPins(p.w))
  val upPins = Output(new LinkPins(p.w))

  /** Credit we return for consumed M.A packets. */
  val dnCred = Output(Bool())

  /** Credit the far side returns for our V.A packets. */
  val upCred = Input(Bool())

  val linkFast = Input(Bool())
  val linkUp   = Output(Bool())
  val linkErr  = Output(Bool())
}

/** ASIC-side link adapter: the bridge between the pins and Borg's own ports.
  *
  * == Why the four interface hazards dissolve ==
  *
  * The governing principle is that '''both adapters stay in Borg's clock domain and
  * only the flit stream crosses'''.  Nothing about Borg's timing contract is
  * transported over a wire, so:
  *
  *  1. `gpuMem.ready` is a one-cycle pulse with no backpressure.  It is never
  *     transported; this adapter '''generates it locally''' when the V.D response
  *     arrives.
  *  2. `gpuMem.req`/`wr` are level-held with no accept handshake.  That is a
  *     feature here, not a problem: it makes the protocol latency-tolerant by
  *     construction.  They are level-sampled in `sVIdle`, exactly as
  *     `MemoryController`'s own idle state does.
  *  3. `mmio.req.ready` and `resp.valid` are gated by `rast.io.autoRunStall` and can
  *     be '''withdrawn mid-flight'''.  Holding `resp.ready` unconditionally high in
  *     `sMResp` means the only event that matters is a fire, so a
  *     withdrawn-then-reasserted `valid` needs no state at all.
  *  4. `waccept` is a per-word burst pull.  It never crosses a wire: the adapter
  *     pulses it locally at one word per cycle to drain the whole burst into
  *     `vBuf` '''before''' transmitting.  That is also what satisfies [[LinkTx]]'s
  *     atomicity requirement -- a packet cannot stall once started.
  *
  * `BorgTestWrapper` already models hazard 3 correctly and was the template.
  *
  * Received traffic is gated on `linkUp` because the training pattern keeps `v`
  * asserted with valid parity, so [[LinkRx]] will decode garbage packets from it.
  */
class BorgLinkSlave(val p: LinkParams) extends Module {
  val io = IO(new BorgLinkSlaveIO(p))

  val clkgen = Module(new BorgLinkClockGen(p, isMaster = false))
  val tx     = Module(new LinkTx(p))
  val rx     = Module(new LinkRx(p, isDn = true))

  clkgen.io.linkFast  := io.linkFast
  clkgen.io.farLinkUp := false.B
  clkgen.io.rxPins    := io.dnPins

  val beatEn = clkgen.io.beatEn
  val linkUp = clkgen.io.linkUp
  io.linkUp := linkUp

  tx.io.beatEn := beatEn
  rx.io.beatEn := beatEn
  rx.io.pins   := io.dnPins
  io.upPins    := tx.io.pins

  val errSticky = RegInit(false.B)
  when(rx.io.err && linkUp) { errSticky := true.B }
  io.linkErr := errSticky

  // -- Receive demux ---------------------------------------------------------
  val rxFire  = rx.io.out.valid && linkUp
  val rxHdr   = rx.io.hdr
  val rxIsM   = rxHdr.chan === LinkChan.M // M.A request from the FPGA
  val rxIsV   = rxHdr.chan === LinkChan.V // V.D response to one of our reads
  val rxFirst = rx.io.out.bits.first
  val rxFlit  = rx.io.out.bits.flit
  val rxIdx   = rx.io.out.bits.idx

  // ==========================================================================
  // MMIO path: M.A in on DN, drive Borg, M.D out on UP
  // ==========================================================================
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
  // Hazard 3: hold ready high so the only observable event is a fire.
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
      // Return the M.A credit only now: the adapter holds exactly one request,
      // so it must not invite another until this one is fully retired.
      mCredRet := !mCredRet
    }.otherwise {
      mFlit := mFlit + 1.U
    }
  }

  // ==========================================================================
  // gpuMem path: Borg's requests out as V.A on UP, V.D responses back on DN
  // ==========================================================================
  val sVIdle :: sVDrain :: sVSend :: sVWait :: Nil = Enum(4)
  val vState = RegInit(sVIdle)

  val vAddr     = Reg(UInt(25.W))
  val vWrite    = Reg(Bool())
  val vWlenLog2 = Reg(UInt(3.W))
  val vBuf      = Reg(Vec(p.maxBurst, UInt(16.W)))
  val vCnt      = RegInit(0.U(log2Ceil(p.maxBurst + 1).W))
  val vFlit     = RegInit(0.U(log2Ceil(p.maxPacketFlits + 1).W))
  val vData     = Reg(UInt(32.W))
  val vReady    = RegInit(false.B)

  val vWords = (1.U << vWlenLog2).asUInt

  // Hazard 1: generated locally, never transported.
  io.gpuMem.ready := vReady
  io.gpuMem.data  := vData
  // Hazard 4: pulled locally, one word per cycle, entirely on this side.
  io.gpuMem.waccept := vState === sVDrain

  val credit = Module(new CreditCounter(p.creditDepth))
  credit.io.returnPin := io.upCred

  vReady := false.B

  switch(vState) {
    is(sVIdle) {
      // Hazard 2: level-sampled, exactly as MemoryController's sIdle does.
      when(io.gpuMem.req) {
        vAddr  := io.gpuMem.addr
        vWrite := false.B
        vFlit  := 0.U
        vState := sVSend
      }.elsewhen(io.gpuMem.wr) {
        vAddr     := io.gpuMem.addr
        vWrite    := true.B
        vWlenLog2 := Log2(io.gpuMem.wlen)
        vCnt      := 0.U
        vState    := sVDrain
      }
    }
    is(sVDrain) {
      // gpuMem.wdata carries only 16 meaningful bits, so one word is one flit.
      vBuf(vCnt) := io.gpuMem.wdata(15, 0)
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
          // AccessAck: burst write complete.
          vReady := true.B
          vState := sVIdle
        }
      }
    }
  }

  val vSendLen = Mux(vWrite, 2.U +& vWords, 2.U)
  val vHdr = LinkHeader(
    LinkChan.V,
    Mux(vWrite, TLOpcode.PutFullData, TLOpcode.Get),
    LinkHeader.vramPayload(vWlenLog2, vAddr(24, 16))
  ).asUInt

  val vBufIdx = (vFlit - 2.U)(log2Ceil(p.maxBurst) - 1, 0)
  tx.io.a.valid := (vState === sVSend) && credit.io.available
  tx.io.a.bits.flit := MuxCase(
    vBuf(vBufIdx),
    Seq(
      (vFlit === 0.U) -> vHdr,
      (vFlit === 1.U) -> vAddr(15, 0)
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
    !(vState === sVIdle && io.gpuMem.wr && !isPow2Wlen(io.gpuMem.wlen)),
    "BorgLinkSlave: gpuMem burst length must be a power of two -- the wire format " +
      "encodes it as log2 so that packet length stays a pure function of the header"
  )

  /** wlen is a power of two (and non-zero). */
  private def isPow2Wlen(wlen: UInt): Bool = (wlen & (wlen - 1.U)) === 0.U && wlen =/= 0.U
}
