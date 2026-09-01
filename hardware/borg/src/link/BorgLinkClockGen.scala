// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

class BorgLinkClockGenIO(val p: LinkParams) extends Bundle {

  /** `link_fast` strap: high selects N=1 (25 MHz beats) instead of the N=2 default. */
  val linkFast = Input(Bool())

  /** Master only: the far side's `link_up` pin. */
  val farLinkUp = Input(Bool())

  /** Slave only: incoming pins, to recover the beat phase from. */
  val rxPins = Input(new LinkPins(p.w))

  /** Beat clock *enable* -- never a divided clock, so this stays one STA domain. */
  val beatEn = Output(Bool())

  /** Link is trained and usable.  Traffic must be gated on this. */
  val linkUp = Output(Bool())

  /** Master only: high while the training pattern should be driven instead of traffic. */
  val trainActive = Output(Bool())

  /** Master only: the pin values to drive while `trainActive`. */
  val trainPins = Output(new LinkPins(p.w))
}

/** Beat-rate generator and link training.
  *
  * == Rate ==
  *
  * The ASIC core clock '''is''' the ULX3S `sysClock`, 25 MHz, so the two chips form
  * one synchronous system offset only by a fixed board delay (mesochronous).  Beat
  * rate is decoupled from core rate by a clock *enable* at ÷N: the core computes at
  * full 25 MHz while the pins move at a rate the off-chip path can actually meet.
  *
  * N=2 (12.5 MHz) at reset, because the hand-computed pad budget closes there with
  * ~24 ns of margin against ~14.6/16 ns of path, whereas N=1 leaves only ~4-5 ns --
  * too thin to bet a one-shot tapeout on.  The `link_fast` strap selects N=1 as a
  * post-silicon lever.  Safe mode is the default, so a mis-strapped board degrades
  * rather than bricking.
  *
  * == Training: find the transitions, not the beats ==
  *
  * [[LinkTx]] registers its pins on `beatEn`, so they are '''stable for the whole
  * beat period'''.  Both sides run at the same period, so the slave samples each
  * distinct pin value exactly once whatever its phase -- logical correctness does
  * not actually depend on alignment.  What alignment buys is '''timing margin''':
  * with N=2 one phase samples immediately after the pins change and the other
  * samples ~40 ns later, in the middle of the eye.  Only the second is safe.
  *
  * So training cannot work by hunting for valid beats (a sparse `v` pattern would
  * not survive the registered path, and a steady one carries no phase information).
  * Instead the master drives a pattern that '''inverts every beat''', the slave
  * oversamples at the core rate and detects where the data changes, and then places
  * its beat `N/2` cycles after that transition -- as far into the eye as the divider
  * resolution allows.
  *
  * After `trainBeats` consecutive transitions at the expected phase the slave raises
  * `link_up`, which the master reads back on a pin before sending real traffic.  At
  * N=1 there is only one phase and training degenerates to a no-op.
  *
  * Framing and parity would eventually catch a gross misalignment, but that makes
  * bring-up a lottery and would leave a marginal-but-working phase undetected. This
  * makes it deterministic, and testable in simulation from a deliberately wrong phase.
  *
  * '''Note''' the training pattern keeps `v` asserted, so a slave-side [[LinkRx]]
  * will happily decode garbage packets during training.  Consumers must ignore
  * received traffic while `link_up` is low.
  *
  * @param isMaster true for the FPGA side (defines the phase), false for the ASIC
  *                 side (recovers it).
  */
class BorgLinkClockGen(val p: LinkParams, val isMaster: Boolean) extends Module {
  val io = IO(new BorgLinkClockGenIO(p))

  /** Alternating-bit seed, so a stuck or shorted lane fails to produce transitions. */
  val trainWord: UInt = Fill(p.w / 8, "hA5".U(8.W))

  // Divider limit is strap-selected: 0 => every cycle is a beat (N=1).
  val limit = Mux(io.linkFast, 0.U, (p.divCycles - 1).U)

  if (p.divCycles == 1) {
    // Degenerate build: no divider, no phase to recover.
    io.beatEn := true.B
    io.linkUp := (if (isMaster) io.farLinkUp else true.B)
    io.trainActive := (if (isMaster) !io.farLinkUp else false.B)
  } else {
    val phase = RegInit(0.U(log2Ceil(p.divCycles).W))
    val beat  = phase === 0.U
    io.beatEn := beat

    if (isMaster) {
      // The master defines the phase; it just free-runs.
      phase := Mux(phase >= limit, 0.U, phase + 1.U)
      io.linkUp      := io.farLinkUp
      io.trainActive := !io.farLinkUp
    } else {
      val up   = RegInit(false.B)
      val good = RegInit(0.U(log2Ceil(p.trainBeats + 1).W))

      // Capture flop, matching LinkRx's, so training observes the same samples the
      // receiver will later decode.  Oversampled at the core rate.
      val inD  = RegNext(io.rxPins.d, 0.U(p.w.W))
      val prev = RegNext(inD, 0.U(p.w.W))
      val changed = inD =/= prev

      // Place the beat N/2 cycles after a transition.  Assigning `phase` at the
      // transition cycle c makes cycle c+k carry phase (P + k - 1), so the beat
      // (phase 0) lands at c + N/2 when P = (N/2 + 1) mod N.
      val relockPhase = ((p.divCycles / 2 + 1) % p.divCycles).U
      // Once locked, transitions arrive N/2 cycles *before* each beat.
      val expectedTransPhase = (p.divCycles / 2).U

      // With `link_fast` there is exactly one phase, so there is nothing to
      // recover and nothing that could be misaligned.  Suppressing training here
      // is not an optimisation: `phase` is pinned to 0 while `expectedTransPhase`
      // is non-zero, so the slave would otherwise consider itself permanently
      // misaligned, relock every cycle and never raise link_up -- which would
      // make the strap useless on silicon, where it cannot be fixed.
      val misaligned = changed && !io.linkFast && (phase =/= expectedTransPhase)

      when(!up && misaligned) {
        phase := relockPhase
        good  := 0.U
      }.otherwise {
        phase := Mux(phase >= limit, 0.U, phase + 1.U)
        when(!up && changed && good < p.trainBeats.U) { good := good + 1.U }
      }

      when(good >= p.trainBeats.U || io.linkFast) { up := true.B }

      io.linkUp      := up
      io.trainActive := false.B
    }
  }

  // -- Training pattern (master) ---------------------------------------------
  // Registered on beatEn exactly like LinkTx, so the training path and the data
  // path have identical launch latency -- otherwise the slave would lock to a
  // phase one cycle off from where real traffic actually lands.  The word inverts
  // every beat so there is a transition for the slave to find.
  val tD = RegInit(trainWord)
  val tV = RegInit(true.B)
  val tP = RegInit(LinkFlit.parity(trainWord, true.B))

  when(io.beatEn) {
    val nextD = ~tD
    tD := nextD
    tV := true.B
    tP := LinkFlit.parity(nextD, true.B)
  }

  io.trainPins.d := tD
  io.trainPins.v := tV
  io.trainPins.p := tP
}
