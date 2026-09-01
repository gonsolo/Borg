// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** TileLink TL-UL opcode encodings, reused verbatim on the wire.
  *
  * Nothing here depends on rocket-chip -- these are just the numbers, copied so
  * that a future thin adapter into a Chipyard/Rocket system does not have to
  * translate.  TL-UL and TL-UH are wire-identical (A+D only, `hasBCE=false`);
  * only TL-C adds the B/C/E channels, which this link does not carry.
  *
  * A- and D-channel opcode spaces overlap by design in TileLink (`PutFullData`
  * and `AccessAck` are both 0).  They never collide here because each physical
  * direction carries exactly one A-type and one D-type channel, and the header's
  * `chan` bit says which:
  *
  *   - DN (FPGA→ASIC): chan M is A-type (MMIO request), chan V is D-type (VRAM response)
  *   - UP (ASIC→FPGA): chan V is A-type (VRAM request),  chan M is D-type (MMIO response)
  */
object TLOpcode {
  // A-channel (request)
  val PutFullData    = 0.U(3.W)
  val PutPartialData = 1.U(3.W)
  val Get            = 4.U(3.W)
  // D-channel (response)
  val AccessAck      = 0.U(3.W)
  val AccessAckData  = 1.U(3.W)
}

/** Logical channel, carried in header bit 15. */
object LinkChan {
  val M = 0.U(1.W) // MMIO  -- FPGA-initiated (Hutt → Borg register/IMEM access)
  val V = 1.U(1.W) // VRAM  -- ASIC-initiated (Borg gpuMem → SDRAM)
}

/** Chip-to-chip link configuration.
  *
  * @param w            Data lanes per direction on the pins.  16 fills the 46-pad
  *                     budget exactly; 8 is the `link_narrow` post-silicon recovery
  *                     mode, which halves the pins and doubles `beatsPerFlit`.
  *                     The 16-bit flit format is unchanged either way.
  * @param creditDepth  Outstanding A-channel packets permitted per direction.  This
  *                     '''must not exceed what the receiver can buffer'''.  Both
  *                     adapters are single-outstanding by construction -- `HuttBus`
  *                     is a single-outstanding req/resp bus and Borg holds `gpuMem`
  *                     asserted until `ready` -- and they hold exactly one request,
  *                     so 1 is the correct value.  Raising it without adding a
  *                     receive queue would let a second packet arrive mid-service
  *                     and be dropped.  It costs nothing here: the sender is
  *                     waiting on the response anyway.
  * @param maxBurstLog2 Largest gpuMem burst the link will carry, as a power of two.
  *                     4 → 16 words, which is what BorgTileFlusher emits.  Bursts are
  *                     encoded log2 so the packet length stays a pure function of the
  *                     header (see [[LinkFlit.flitsUp]]).
  * @param divLog2      Beat-rate divider at reset, as a power of two.  1 → ÷2, i.e.
  *                     12.5 MHz beats from the 25 MHz core clock, which is what the
  *                     hand-computed pad budget closes at with ~24 ns of margin.
  *                     The `link_fast` strap selects 0 (÷1, 25 MHz) post-silicon.
  * @param gapBeats     Idle beats forced between packets.  Must be ≥1: it is what
  *                     makes the receiver self-resynchronizing after a framing error.
  * @param trainBeats   Consecutive good training beats required before `link_up`.
  */
case class LinkParams(
    w: Int = 16,
    creditDepth: Int = 1,
    maxBurstLog2: Int = 4,
    divLog2: Int = 1,
    gapBeats: Int = 1,
    trainBeats: Int = 16
) {
  require(w == 16 || w == 8, s"w must be 16 (normal) or 8 (link_narrow), got $w")
  require(creditDepth >= 1, "creditDepth must be at least 1")
  require(maxBurstLog2 >= 0 && maxBurstLog2 <= 6, "gpuMem wlen is 7 bits, so log2 ≤ 6")
  require(gapBeats >= 1, "gapBeats must be ≥1 -- it is the resynchronization point")

  /** Every flit is 16 bits; `w` only changes how many beats one takes. */
  val flitBits: Int = 16
  val beatsPerFlit: Int = flitBits / w

  /** Largest burst in words, and the longest packet on the wire (a V.A write). */
  val maxBurst: Int = 1 << maxBurstLog2
  val maxPacketFlits: Int = 2 + maxBurst

  val divCycles: Int = 1 << divLog2
}
