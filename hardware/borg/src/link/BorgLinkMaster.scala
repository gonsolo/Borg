// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._
import borg.{BorgMmioIf, GpuMemIO}
import hutt.HuttBus

class BorgLinkMasterIO(val p: LinkParams) extends Bundle with BorgMmioIf {

  /** Identical to `BorgIO`'s `mmio` and `gpuMem`, so this is a drop-in for Borg. */
  val mmio = Flipped(new HuttBus(10))

  val gpuMem = new GpuMemIO

  val dnPins = Output(new LinkPins(p.w))
  val upPins = Input(new LinkPins(p.w))

  /** Credit the far side returns for our M.A packets. */
  val dnCred = Input(Bool())

  /** Credit we return for consumed V.A packets. */
  val upCred = Output(Bool())

  val linkFast = Input(Bool())
  /** `link_narrow` strap: halve the lanes at runtime (narrowCapable builds only). */
  val narrow   = Input(Bool())

  /** The ASIC's `link_up` pin. */
  val farLinkUp = Input(Bool())

  val linkUp  = Output(Bool())
  val linkErr = Output(Bool())
}

/** FPGA-side link adapter, the mirror of [[BorgLinkSlave]].
  *
  * Its IO matches `BorgIO`, so the SoC cannot tell it from a local Borg. The master's
  * divider sets the beat phase; it trains until the slave reports `link_up`.
  */
class BorgLinkMaster(val p: LinkParams) extends Module {
  val io = IO(new BorgLinkMasterIO(p))

  val clkgen = Module(new BorgLinkClockGen(p, isMaster = true))
  val tx     = Module(new LinkTx(p))
  val rx     = Module(new LinkRx(p, isDn = false))

  clkgen.io.linkFast  := io.linkFast
  clkgen.io.narrow    := io.narrow
  clkgen.io.farLinkUp := io.farLinkUp
  clkgen.io.rxPins    := DontCare

  val beatEn = clkgen.io.beatEn
  // Report link_up only after a full inter-packet gap: the slave may still be inside a
  // training "packet", and the idle beats are where it resynchronizes (`synced`).
  val settle = RegInit(0.U(log2Ceil(p.gapBeats + 2).W))
  when(!clkgen.io.linkUp) { settle := 0.U }
    .elsewhen(beatEn && settle <= p.gapBeats.U) { settle := settle + 1.U }
  val linkUp = clkgen.io.linkUp && settle > p.gapBeats.U
  io.linkUp := linkUp

  tx.io.beatEn := beatEn
  tx.io.narrow := io.narrow
  rx.io.beatEn := beatEn
  rx.io.narrow := io.narrow
  rx.io.pins   := io.upPins

  // Drive the training pattern until the far side reports it has locked phase.
  io.dnPins := Mux(clkgen.io.trainActive, clkgen.io.trainPins, tx.io.pins)

  // Received traffic counts from the first idle beat after link_up (see BorgLinkSlave).
  val synced = RegInit(false.B)
  when(linkUp && rx.io.idle) { synced := true.B }

  val errSticky = RegInit(false.B)
  when(rx.io.err && linkUp && synced) { errSticky := true.B }
  io.linkErr := errSticky

  val rxFire  = rx.io.out.valid && linkUp && synced
  val rxHdr   = rx.io.hdr
  val rxIsV   = rxHdr.chan === LinkChan.V // V.A request from Borg
  val rxIsM   = rxHdr.chan === LinkChan.M // M.D response to one of our requests
  val rxFirst = rx.io.out.bits.first
  val rxFlit  = rx.io.out.bits.flit
  val rxIdx   = rx.io.out.bits.idx

  // MMIO path: SoC request -> M.A on DN, M.D back on UP -> SoC response
  val sMIdle :: sMSend :: sMWait :: sMResp :: Nil = Enum(4)
  val mState = RegInit(sMIdle)

  val mAddr  = Reg(UInt(10.W))
  val mSize  = Reg(UInt(2.W))
  val mWrite = Reg(Bool())
  val mData  = Reg(UInt(32.W))
  val mResp  = Reg(UInt(32.W))
  val mFlit  = RegInit(0.U(2.W))

  val mCredit = Module(new CreditCounter(p.creditDepth))
  mCredit.io.returnPin := io.dnCred

  io.mmio.req.ready := (mState === sMIdle) && linkUp && mCredit.io.available

  when(io.mmio.req.fire) {
    mAddr  := io.mmio.req.bits.addr
    mData  := io.mmio.req.bits.data
    mWrite := io.mmio.req.bits.write
    mSize  := io.mmio.req.bits.size
    mFlit  := 0.U
    mState := sMSend
  }

  val mSendLen = Mux(mWrite, 3.U, 1.U)
  val mHdr = LinkHeader(
    LinkChan.M,
    Mux(mWrite, TLOpcode.PutFullData, TLOpcode.Get),
    LinkHeader.mmioPayload(mSize, mAddr)
  ).asUInt

  tx.io.a.valid := mState === sMSend
  tx.io.a.bits.flit := MuxLookup(mFlit, mHdr)(
    Seq(0.U -> mHdr, 1.U -> mData(15, 0), 2.U -> mData(31, 16))
  )
  tx.io.a.bits.last := mFlit === (mSendLen - 1.U)

  mCredit.io.consume := tx.io.a.fire && tx.io.a.bits.last

  when(mState === sMSend && tx.io.a.fire) {
    when(tx.io.a.bits.last) {
      mState := sMWait
    }.otherwise {
      mFlit := mFlit + 1.U
    }
  }

  when(mState === sMWait && rxFire && rxIsM) {
    when(rxHdr.opcode === TLOpcode.AccessAckData) {
      when(rxIdx === 1.U) { mResp := Cat(mResp(31, 16), rxFlit) }
        .elsewhen(rxIdx === 2.U) {
          mResp  := Cat(rxFlit, mResp(15, 0))
          mState := sMResp
        }
    }.otherwise {
      // AccessAck: write complete, resp.bits is don't-care for writes.
      mState := sMResp
    }
  }

  io.mmio.resp.valid := mState === sMResp
  io.mmio.resp.bits  := mResp
  when(io.mmio.resp.fire) { mState := sMIdle }

  // gpuMem path: V.A in on UP -> replay on the local memory port -> V.D on DN
  val sVIdle :: sVAddrLo :: sVAddrHi :: sVCollect :: sVIssue :: sVSend :: Nil = Enum(6)
  val vState = RegInit(sVIdle)

  val vAddr     = Reg(UInt(GpuMemIO.AddrBits.W))
  val vWrite    = Reg(Bool())
  val vWlenLog2 = Reg(UInt(3.W))
  val vBuf      = Reg(Vec(p.maxBurst, UInt(16.W)))
  val vCnt      = RegInit(0.U(log2Ceil(p.maxBurst + 1).W))
  val vFlit     = RegInit(0.U(2.W))
  val vData     = Reg(UInt(32.W))

  val vWords = (1.U << vWlenLog2).asUInt
  // vCnt counts to maxBurst inclusive, one bit wider than a vBuf index.
  val vCntIdx = vCnt(log2Ceil(p.maxBurst) - 1, 0)

  io.gpuMem.addr  := vAddr
  io.gpuMem.req   := (vState === sVIssue) && !vWrite
  io.gpuMem.wr    := (vState === sVIssue) && vWrite
  // LinkRx rejects wlenLog2 > maxBurstLog2, so vWords fits.
  io.gpuMem.wlen  := vWords(6, 0)
  io.gpuMem.wdata := vBuf(vCntIdx)

  val vCredRet = RegInit(false.B)
  io.upCred := vCredRet

  switch(vState) {
    is(sVIdle) {
      when(rxFire && rxIsV && rxFirst) {
        vWrite    := rxHdr.opcode =/= TLOpcode.Get
        vWlenLog2 := LinkHeader.vramWlenLog2(rxHdr)
        vCnt      := 0.U
        vState    := sVAddrLo
      }
    }
    is(sVAddrLo) {
      when(rxFire) {
        vAddr  := Cat(vAddr(31, 16), rxFlit)
        vState := sVAddrHi
      }
    }
    is(sVAddrHi) {
      when(rxFire) {
        vAddr := Cat(rxFlit, vAddr(15, 0))
        when(vWrite) { vState := sVCollect }.otherwise { vState := sVIssue }
      }
    }
    is(sVCollect) {
      when(rxFire) {
        vBuf(vCntIdx) := rxFlit
        vCnt       := vCnt + 1.U
        when(vCnt === (vWords - 1.U)) {
          vCnt   := 0.U
          vState := sVIssue
        }
      }
    }
    is(sVIssue) {
      // Advance the burst word on each waccept, exactly as Borg would locally.
      when(io.gpuMem.waccept) { vCnt := vCnt + 1.U }
      when(io.gpuMem.ready) {
        vData  := io.gpuMem.data
        vCnt   := 0.U
        vFlit  := 0.U
        vState := sVSend
      }
    }
    is(sVSend) { /* advanced by the transmit handshake below */ }
  }

  val vSendLen = Mux(vWrite, 1.U, 3.U)
  val vHdr = LinkHeader(
    LinkChan.V,
    Mux(vWrite, TLOpcode.AccessAck, TLOpcode.AccessAckData),
    0.U
  ).asUInt

  tx.io.d.valid := vState === sVSend
  tx.io.d.bits.flit := MuxLookup(vFlit, vHdr)(
    Seq(0.U -> vHdr, 1.U -> vData(15, 0), 2.U -> vData(31, 16))
  )
  tx.io.d.bits.last := vFlit === (vSendLen - 1.U)

  when(vState === sVSend && tx.io.d.fire) {
    when(tx.io.d.bits.last) {
      vState   := sVIdle
      vCredRet := !vCredRet
    }.otherwise {
      vFlit := vFlit + 1.U
    }
  }
}
