// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

class LinkTxIO(val p: LinkParams) extends Bundle {
  val beatEn = Input(Bool())
  val narrow = Input(Bool())
  val a      = Flipped(Decoupled(new LinkFlitStream))
  val d      = Flipped(Decoupled(new LinkFlitStream))
  val pins   = Output(new LinkPins(p.w))

  val busy = Output(Bool())   // a packet or its trailing gap is in flight
}

/** Link transmitter: D over A, non-preemptive (a D only answers a granted A, so A cannot starve),
  * flits sent LSB slice first, odd parity over {d, v} on every beat, and an idle gap after every
  * packet for [[LinkRx]] to resynchronize. Pins are registered and advance on the `beatEn` clock
  * enable. Packets are atomic: a producer must supply a flit every beat until `last`.
  */
class LinkTx(val p: LinkParams) extends Module {
  val io = IO(new LinkTxIO(p))

  val sIdle :: sSend :: sGap :: Nil = Enum(3)
  val state = RegInit(sIdle)

  val ownerD = RegInit(false.B)
  val subCnt = RegInit(0.U(log2Ceil(math.max(if (p.narrowCapable) 2 else p.beatsPerFlit, 2)).W))
  val gapCnt = RegInit(0.U(log2Ceil(p.gapBeats + 1).W))

  val pickD = io.d.valid
  val selD  = Mux(state === sSend, ownerD, pickD)

  val srcValid = Mux(selD, io.d.valid, io.a.valid)
  val srcFlit  = Mux(selD, io.d.bits.flit, io.a.bits.flit)
  val srcLast  = Mux(selD, io.d.bits.last, io.a.bits.last)

  // The Scala `if` avoids elaborating a 0-width dynamic Vec index at w=16.
  val beatData: UInt =
    if (p.narrowCapable)
      // Narrow drives d[15:8] low rather than floating.
      Mux(io.narrow,
          Mux(subCnt === 0.U, srcFlit(7, 0), srcFlit(15, 8)).pad(p.w),
          srcFlit)
    else if (p.beatsPerFlit == 1) srcFlit
    else VecInit(Seq.tabulate(p.beatsPerFlit)(i => srcFlit(p.w * (i + 1) - 1, p.w * i)))(subCnt)

  val lastBeatOfFlit =
    if (p.narrowCapable) Mux(io.narrow, subCnt === 1.U, true.B)
    else subCnt === (p.beatsPerFlit - 1).U

  // sIdle emits the first beat directly rather than spend a beat on the transition.
  val emitting = (state === sIdle && srcValid) || (state === sSend)

  val outD = RegInit(0.U(p.w.W))
  val outV = RegInit(false.B)
  // Idle carries parity 1, so a dead all-zero cable differs from an idle line.
  val outP = RegInit(true.B)

  val nextD = Mux(emitting, beatData, 0.U)
  val nextV = emitting

  when(io.beatEn) {
    outD := nextD
    outV := nextV
    outP := LinkFlit.parityW(nextD, nextV, io.narrow, p)
  }

  io.pins.d := outD
  io.pins.v := outV
  io.pins.p := outP

  val flitDone = emitting && lastBeatOfFlit && io.beatEn
  io.a.ready := flitDone && !selD
  io.d.ready := flitDone && selD

  when(io.beatEn) {
    switch(state) {
      is(sIdle) {
        when(srcValid) {
          ownerD := selD
          when(lastBeatOfFlit) {
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
