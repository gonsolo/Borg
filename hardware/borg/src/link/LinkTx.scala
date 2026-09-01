// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** Link transmitter: packet arbiter + flit serializer for one direction.
  *
  * Consumes two flit streams -- `a` (requests) and `d` (responses) -- and drives
  * the pins for one direction.  Responsibilities:
  *
  *   - **Arbitration**, fixed priority D over A, non-preemptible once a packet
  *     starts.  Starvation is structurally impossible: a D packet only exists in
  *     response to a granted A, and credits cap outstanding A packets.  D-first
  *     also unblocks the cases that actually stall -- a V.D releases a Borg that
  *     is holding `gpuMem.req` high.
  *   - **Serialization** of each 16-bit flit into `beatsPerFlit` beats of `w`
  *     bits, LSB slice first.  Degenerate (one beat) in the normal w=16 build.
  *   - **The inter-packet gap.**  At least one idle beat follows every packet;
  *     this is what lets [[LinkRx]] resynchronize after a framing error.
  *   - **Odd parity** over `{d, v}`, on every beat including idle ones.
  *
  * Everything advances only on `beatEn`, a clock *enable* rather than a divided
  * clock, so the whole link stays in one STA domain with the core.
  *
  * The pins are registered.  That gives the launch flop the off-chip timing
  * budget assumes, and keeps the pins from glitching mid-beat when an input
  * `valid` changes -- the far side samples them on its own clock edge.
  *
  * '''Packets are atomic''': once a producer starts one it must supply a flit on
  * every beat until `last`.  Both adapters satisfy this by buffering a whole
  * packet before raising `valid` (which is also why [[BorgLinkSlave]] drains a
  * gpuMem burst locally before transmitting).  Violating it is a framing error at
  * the far end, so it is asserted here rather than tolerated.
  */
class LinkTxIO(val p: LinkParams) extends Bundle {
  val beatEn = Input(Bool())
  val a      = Flipped(Decoupled(new LinkFlitStream))
  val d      = Flipped(Decoupled(new LinkFlitStream))
  val pins   = Output(new LinkPins(p.w))

  /** High while a packet is in flight or the gap after it has not elapsed. */
  val busy = Output(Bool())
}

class LinkTx(val p: LinkParams) extends Module {
  val io = IO(new LinkTxIO(p))

  val sIdle :: sSend :: sGap :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val ownerD = RegInit(false.B)
  val subCnt = RegInit(0.U(log2Ceil(math.max(p.beatsPerFlit, 2)).W))
  val gapCnt = RegInit(0.U(log2Ceil(p.gapBeats + 1).W))

  // -- Source selection -------------------------------------------------------
  val pickD = io.d.valid
  val selD  = Mux(state === sSend, ownerD, pickD)

  val srcValid = Mux(selD, io.d.valid, io.a.valid)
  val srcFlit  = Mux(selD, io.d.bits.flit, io.a.bits.flit)
  val srcLast  = Mux(selD, io.d.bits.last, io.a.bits.last)

  // -- Beat slice -------------------------------------------------------------
  // At w=16 the flit is one beat and there is no index at all; the Scala `if`
  // keeps a genuinely 0-width dynamic Vec index from ever being elaborated.
  val beatData: UInt =
    if (p.beatsPerFlit == 1) srcFlit
    else VecInit(Seq.tabulate(p.beatsPerFlit)(i => srcFlit(p.w * (i + 1) - 1, p.w * i)))(subCnt)

  val lastBeatOfFlit = subCnt === (p.beatsPerFlit - 1).U

  // Emitting on this beat?  sIdle emits the first beat directly rather than
  // burning a beat on the transition -- latency is what hurts on this link.
  val emitting = (state === sIdle && srcValid) || (state === sSend)

  // -- Registered pins --------------------------------------------------------
  val outD = RegInit(0.U(p.w.W))
  val outV = RegInit(false.B)
  // parity(0, false) is odd, i.e. true: idle beats are still valid parity, so a
  // dead cable reading all-zero is distinguishable from a real idle line.
  val outP = RegInit(true.B)

  val nextD = Mux(emitting, beatData, 0.U)
  val nextV = emitting

  when(io.beatEn) {
    outD := nextD
    outV := nextV
    outP := LinkFlit.parity(nextD, nextV)
  }

  io.pins.d := outD
  io.pins.v := outV
  io.pins.p := outP

  // -- Handshake --------------------------------------------------------------
  val flitDone = emitting && lastBeatOfFlit && io.beatEn
  io.a.ready := flitDone && !selD
  io.d.ready := flitDone && selD

  // -- State ------------------------------------------------------------------
  when(io.beatEn) {
    switch(state) {
      is(sIdle) {
        when(srcValid) {
          ownerD := selD
          when(lastBeatOfFlit) {
            // Single-beat flit: it is already complete.
            subCnt := 0.U
            when(srcLast) {
              state  := sGap
              gapCnt := p.gapBeats.U
            }.otherwise {
              state := sSend
            }
          }.otherwise {
            subCnt := 1.U
            state  := sSend
          }
        }
      }
      is(sSend) {
        when(lastBeatOfFlit) {
          subCnt := 0.U
          when(srcLast) {
            state  := sGap
            gapCnt := p.gapBeats.U
          }
        }.otherwise {
          subCnt := subCnt + 1.U
        }
      }
      is(sGap) {
        when(gapCnt <= 1.U) {
          state  := sIdle
          gapCnt := 0.U
        }.otherwise {
          gapCnt := gapCnt - 1.U
        }
      }
    }
  }

  io.busy := state =/= sIdle

  when(io.beatEn && state === sSend) {
    assert(srcValid, "LinkTx: producer dropped valid mid-packet -- packets must be atomic")
  }
}
