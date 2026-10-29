// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

class LinkRxFlit(val maxFlits: Int) extends Bundle {
  val flit  = UInt(16.W)
  val first = Bool()
  val last  = Bool()
  val idx   = UInt(log2Ceil(maxFlits + 1).W)
}

class LinkRxIO(val p: LinkParams) extends Bundle {
  val narrow = Input(Bool())
  val beatEn = Input(Bool())
  val pins   = Input(new LinkPins(p.w))

  val out = Valid(new LinkRxFlit(p.maxPacketFlits))

  val hdr = Output(new LinkHeader)   // valid from `first` on

  val err = Output(Bool())           // parity or framing error pulse

  val errParity = Output(Bool())     // the last error was parity, not framing

  // A parity-good v=0 beat: any packet in progress aborts, the next beat starts on a header.
  val idle = Output(Bool())
}

/** Link receiver: flit deserializer and framing, a two-state FSM because packet length is a
  * function of the header flit alone. Errors are reported, never retried: odd parity over {d, v}
  * is checked every beat, so a disconnected cable (all zero) never comes up, and `v` dropping
  * mid-packet aborts it. No backpressure: credits upstream guarantee the consumer accepts.
  *
  * @param isDn ASIC-side receiver (M.A, V.D) rather than FPGA-side (V.A, M.D).
  */
class LinkRx(val p: LinkParams, val isDn: Boolean) extends Module {
  val io = IO(new LinkRxIO(p))

  // Sampled every core cycle: data is stable for a beat, BorgLinkClockGen aligns the phase.
  val inD = RegNext(io.pins.d, 0.U(p.w.W))
  val inV = RegNext(io.pins.v, false.B)
  val inP = RegNext(io.pins.p, true.B)

  val parityOk = LinkFlit.parityW(inD, inV, io.narrow, p) === inP
  io.idle := io.beatEn && parityOk && !inV

  val sIdle :: sPayload :: Nil = Enum(2)
  val state = RegInit(sIdle)

  val subCnt   = RegInit(0.U(log2Ceil(math.max(if (p.narrowCapable) 2 else p.beatsPerFlit, 2)).W))
  val flitAcc  = RegInit(0.U(16.W))
  val remain   = RegInit(0.U(log2Ceil(p.maxPacketFlits + 1).W))
  val flitIdx  = RegInit(0.U(log2Ceil(p.maxPacketFlits + 1).W))
  val hdrReg   = RegInit(0.U.asTypeOf(new LinkHeader))

  val lastBeatOfFlit =
    if (p.narrowCapable) Mux(io.narrow, subCnt === 1.U, true.B)
    else subCnt === (p.beatsPerFlit - 1).U

  // LSB slice first, as LinkTx sends it.
  val assembled: UInt =
    if (p.narrowCapable) {
      // Narrow: the live half is inD[7:0].
      val narrowAsm = Wire(UInt(16.W))
      narrowAsm := Mux(subCnt === 0.U, inD(7, 0), Cat(inD(7, 0), flitAcc(7, 0)))
      Mux(io.narrow, narrowAsm, inD)
    } else if (p.beatsPerFlit == 1) inD
    else {
      val shifted = Wire(UInt(16.W))
      shifted := (inD << (subCnt * p.w.U))(15, 0)
      shifted | flitAcc
    }

  io.out.valid     := false.B
  io.out.bits.flit := assembled
  io.out.bits.first := false.B
  io.out.bits.last  := false.B
  io.out.bits.idx   := flitIdx
  io.hdr            := hdrReg
  io.err            := false.B

  val errParityReg = RegInit(false.B)
  io.errParity := errParityReg

  def abort(isParity: Bool): Unit = {
    io.err       := true.B
    errParityReg := isParity
    state        := sIdle
    subCnt       := 0.U
    flitAcc      := 0.U
    remain       := 0.U
    flitIdx      := 0.U
  }

  when(io.beatEn) {
    when(!parityOk) {
      // Parity is checked even when idle, so this catches a dead cable too.
      abort(true.B)
    }.otherwise {
      switch(state) {
        is(sIdle) {
          when(inV) {
            when(lastBeatOfFlit) {
              val h   = LinkHeader.fromBits(assembled)
              val len = if (isDn) LinkFlit.flitsDn(h) else LinkFlit.flitsUp(h)

              // A corrupted header can decode to a length this build never emits: a framing
              // error, which bounds how far one bad header drags the receiver off alignment.
              val lenOk = len >= 1.U && len <= p.maxPacketFlits.U

              when(!lenOk) {
                abort(false.B)
              }.otherwise {
                hdrReg := h
                io.hdr := h

                io.out.valid      := true.B
                io.out.bits.flit  := assembled
                io.out.bits.first := true.B
                io.out.bits.last  := len === 1.U
                io.out.bits.idx   := 0.U

                subCnt  := 0.U
                flitAcc := 0.U
                when(len === 1.U) {
                  state   := sIdle
                  flitIdx := 0.U
                }.otherwise {
                  state   := sPayload
                  // `len` is 2 bits on DN but up to 8 on UP: pad before slicing.
                  remain  := (len - 1.U).pad(remain.getWidth)(remain.getWidth - 1, 0)
                  flitIdx := 1.U
                }
              }
            }.otherwise {
              subCnt  := subCnt + 1.U
              flitAcc := assembled
            }
          }
          // The gap also drops a half narrow flit, as training can stop on an odd sub-beat.
          when(!inV) {
            subCnt  := 0.U
            flitAcc := 0.U
          }
        }

        is(sPayload) {
          when(!inV) {
            abort(false.B)
          }.otherwise {
            when(lastBeatOfFlit) {
              io.out.valid      := true.B
              io.out.bits.flit  := assembled
              io.out.bits.first := false.B
              io.out.bits.last  := remain === 1.U
              io.out.bits.idx   := flitIdx

              subCnt  := 0.U
              flitAcc := 0.U
              remain  := remain - 1.U
              flitIdx := flitIdx + 1.U
              when(remain === 1.U) {
                state   := sIdle
                flitIdx := 0.U
              }
            }.otherwise {
              subCnt  := subCnt + 1.U
              flitAcc := assembled
            }
          }
        }
      }
    }
  }
}
