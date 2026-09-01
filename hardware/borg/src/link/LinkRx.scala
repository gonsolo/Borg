// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** One received flit, with its position in the packet. */
class LinkRxFlit(val maxFlits: Int) extends Bundle {
  val flit  = UInt(16.W)
  val first = Bool()
  val last  = Bool()
  val idx   = UInt(log2Ceil(maxFlits + 1).W)
}

/** Link receiver: flit deserializer + framing for one direction.
  *
  * A two-state FSM, which is only possible because packet length is a pure
  * function of the header flit -- see [[LinkFlit.flitsDn]] / [[LinkFlit.flitsUp]]
  * and the log2 burst encoding that makes it so.
  *
  * Error handling is detect-and-report, never retry.  There is no replay buffer
  * and no sequence numbering; recovery is a link reset driven by the FPGA.  That
  * is the right trade here because the link is short, parallel and slow, so the
  * expected error rate is essentially zero -- errors mean a broken cable or a
  * timing violation, neither of which a retry would fix.
  *
  *   - **Parity** is odd over `{d, v}` and is checked on '''every''' beat,
  *     including idle ones.  A disconnected cable reads all-zero, which is even,
  *     so it fails continuously and `link_up` can never assert.
  *   - **Framing**: `v` dropping mid-packet aborts it.  Combined with the
  *     transmitter's mandatory inter-packet gap, this makes the link
  *     self-resynchronizing: after any error the receiver returns to idle and the
  *     next gap re-establishes packet alignment.
  *
  * There is no backpressure -- bits arrive off a wire whether or not the consumer
  * is ready -- so `out` is a [[Valid]].  Credit-based flow control upstream is
  * what guarantees the adapter can always accept.
  *
  * @param isDn true for the ASIC-side receiver (decodes FPGA→ASIC packets: M.A and
  *             V.D), false for the FPGA-side receiver (V.A and M.D).
  */
class LinkRxIO(val p: LinkParams) extends Bundle {
  val beatEn = Input(Bool())
  val pins   = Input(new LinkPins(p.w))

  val out = Valid(new LinkRxFlit(p.maxPacketFlits))

  /** Header of the packet currently being received (valid from `first` on). */
  val hdr = Output(new LinkHeader)

  /** Single-cycle pulse on a parity or framing error. */
  val err = Output(Bool())

  /** Sticky-ish view for debug: last error was parity (vs framing). */
  val errParity = Output(Bool())
}

class LinkRx(val p: LinkParams, val isDn: Boolean) extends Module {
  val io = IO(new LinkRxIO(p))

  // -- Capture flops ----------------------------------------------------------
  // The real off-chip capture registers.  Sampled every core cycle; the data is
  // stable for a whole beat period, so whichever core edge lands inside the beat
  // sees the same value.  Beat-phase alignment is BorgLinkClockGen's job.
  val inD = RegNext(io.pins.d, 0.U(p.w.W))
  val inV = RegNext(io.pins.v, false.B)
  val inP = RegNext(io.pins.p, true.B)

  val parityOk = LinkFlit.parity(inD, inV) === inP

  // -- Flit assembly ----------------------------------------------------------
  val sIdle :: sPayload :: Nil = Enum(2)
  val state = RegInit(sIdle)

  val subCnt   = RegInit(0.U(log2Ceil(math.max(p.beatsPerFlit, 2)).W))
  val flitAcc  = RegInit(0.U(16.W))
  val remain   = RegInit(0.U(log2Ceil(p.maxPacketFlits + 1).W))
  val flitIdx  = RegInit(0.U(log2Ceil(p.maxPacketFlits + 1).W))
  val hdrReg   = RegInit(0.U.asTypeOf(new LinkHeader))

  val lastBeatOfFlit = subCnt === (p.beatsPerFlit - 1).U

  // Assemble LSB slice first, matching LinkTx's serialization order.
  val assembled: UInt =
    if (p.beatsPerFlit == 1) inD
    else {
      val shifted = Wire(UInt(16.W))
      shifted := (inD << (subCnt * p.w.U))(15, 0)
      shifted | flitAcc
    }

  // -- Defaults ---------------------------------------------------------------
  io.out.valid     := false.B
  io.out.bits.flit := assembled
  io.out.bits.first := false.B
  io.out.bits.last  := false.B
  io.out.bits.idx   := flitIdx
  io.hdr            := hdrReg
  io.err            := false.B

  val errParityReg = RegInit(false.B)
  io.errParity := errParityReg

  /** Abort the packet in progress and go back to hunting for a header. */
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
              // Header complete -- decode the packet length from it alone.
              val h   = LinkHeader.fromBits(assembled)
              val len = if (isDn) LinkFlit.flitsDn(h) else LinkFlit.flitsUp(h)

              // A corrupted header can decode to a length this build never emits
              // (the 3-bit wlenLog2 field spans up to 130 flits, `maxPacketFlits`
              // is 18 at maxBurstLog2=4).  Treat that as a framing error rather
              // than trusting it: it bounds how far one bad header can drag the
              // receiver off alignment, and it is a real recovery path, not an
              // assertion -- garbage headers are exactly what follows an error.
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
                  // `len` is 2 bits on DN but up to 8 on UP, so pad before
                  // slicing -- the raw slice over-indexes the narrow case.
                  remain  := (len - 1.U).pad(remain.getWidth)(remain.getWidth - 1, 0)
                  flitIdx := 1.U
                }
              }
            }.otherwise {
              subCnt  := subCnt + 1.U
              flitAcc := assembled
            }
          }
          // v=0 while idle is just the inter-packet gap: nothing to do.
        }

        is(sPayload) {
          when(!inV) {
            // Valid dropped mid-packet: framing error.
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
