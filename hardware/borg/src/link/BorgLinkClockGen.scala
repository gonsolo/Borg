// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

class BorgLinkClockGenIO(val p: LinkParams) extends Bundle {

  val linkFast = Input(Bool())    // N=1 (25 MHz beats) instead of the N=2 default
  val narrow   = Input(Bool())    // only consulted when p.narrowCapable

  val farLinkUp = Input(Bool())   // master only
  val rxPins = Input(new LinkPins(p.w))   // slave only: to recover the beat phase from

  // A clock enable, never a divided clock, so the link stays one STA domain.
  val beatEn = Output(Bool())

  val linkUp = Output(Bool())   // traffic must be gated on this

  val trainActive = Output(Bool())   // master only
  val trainPins = Output(new LinkPins(p.w))

  // Slave only: trainGood stuck at 0 means no transitions arrive; counting up and
  // resetting means the phase never locks.
  val dbgTrainGood = Output(UInt(log2Ceil(p.trainBeats + 1).W))
  val dbgChanged   = Output(Bool())
}

/** Beat-rate generator and link training. Both chips run the same 25 MHz clock; beats move
  * at ÷N (N=2 by default for pad-timing margin, `link_fast` selects N=1). The master drives a
  * word that inverts every beat; the slave oversamples, finds the transition and places its
  * beat N/2 cycles later, mid-eye, then raises link_up after `trainBeats` good transitions.
  * Training keeps `v` high, so receivers must ignore traffic while link_up is low.
  *
  * @param isMaster true for the FPGA side (defines the phase), false for the ASIC side.
  * @param dnPinsPreRegistered `rxPins` passed one extra falling-edge register (the ASIC
  *   slave's dnPinsIn); shifts the expected phase by that cycle, or training desyncs silently.
  */
class BorgLinkClockGen(
    val p: LinkParams,
    val isMaster: Boolean,
    val dnPinsPreRegistered: Boolean = false
) extends Module {
  val io = IO(new BorgLinkClockGenIO(p))

  // Alternating bits, so a stuck or shorted lane fails to produce transitions.
  val trainWord: UInt = Fill(p.w / 8, "hA5".U(8.W))

  val limit = Mux(io.linkFast, 0.U, (p.divCycles - 1).U)

  if (p.divCycles == 1) {
    io.beatEn := true.B
    io.linkUp := (if (isMaster) io.farLinkUp else true.B)
    io.trainActive := (if (isMaster) !io.farLinkUp else false.B)
    io.dbgTrainGood := 0.U
    io.dbgChanged   := false.B
  } else {
    val phase = RegInit(0.U(log2Ceil(p.divCycles).W))
    val beat  = phase === 0.U
    io.beatEn := beat

    if (isMaster) {
      // The master defines the phase.
      phase := Mux(phase >= limit, 0.U, phase + 1.U)
      io.linkUp      := io.farLinkUp
      io.trainActive := !io.farLinkUp
      io.dbgTrainGood := 0.U
      io.dbgChanged   := false.B
    } else {
      val up   = RegInit(false.B)
      val good = RegInit(0.U(log2Ceil(p.trainBeats + 1).W))

      // Same capture flop as LinkRx, so training sees the samples the receiver decodes.
      val inD  = RegNext(io.rxPins.d, 0.U(p.w.W))
      val prev = RegNext(inD, 0.U(p.w.W))
      // Only the live lanes: a noisy floating lane could fake transitions.
      val changed =
        if (!p.narrowCapable) inD =/= prev
        else Mux(io.narrow, inD(7, 0) =/= prev(7, 0), inD =/= prev)

      // Setting phase P at transition cycle c puts phase 0 at c + N/2 when P = (N/2 + 1) mod N.
      val preRegShift = if (dnPinsPreRegistered) 1 else 0
      val relockPhase = ((p.divCycles / 2 + 1 + preRegShift) % p.divCycles).U
      // Once locked, transitions arrive N/2 cycles *before* each beat.
      val expectedTransPhase = ((p.divCycles / 2 + preRegShift) % p.divCycles).U

      // With link_fast phase stays 0 but expectedTransPhase is not, so the slave would
      // relock forever and never raise link_up.
      val misaligned = changed && !io.linkFast && (phase =/= expectedTransPhase)

      when(!up && misaligned) {
        phase := relockPhase
        good  := 0.U
      }.otherwise {
        phase := Mux(phase >= limit, 0.U, phase + 1.U)
        when(!up && changed && good < p.trainBeats.U) { good := good + 1.U }
      }

      // Fast mode still trains: raising link_up early decodes the training word as packets.
      when(good >= p.trainBeats.U) { up := true.B }

      io.linkUp      := up
      io.trainActive := false.B
      io.dbgTrainGood := good
      io.dbgChanged   := changed
    }
  }

  // Master training pattern, registered on beatEn like LinkTx so both paths launch alike.
  val tD = RegInit(trainWord)
  val tV = RegInit(true.B)
  val tP = RegInit(LinkFlit.parity(trainWord, true.B))

  when(io.beatEn) {
    // In narrow mode d[15:8] stays low.
    val nextD =
      if (!p.narrowCapable) ~tD
      else Mux(io.narrow, Cat(0.U((p.w - 8).W), ~tD(7, 0)), ~tD)
    tD := nextD
    tV := true.B
    tP := LinkFlit.parityW(nextD, true.B, io.narrow, p)
  }

  io.trainPins.d := tD
  io.trainPins.v := tV
  io.trainPins.p := tP
}
