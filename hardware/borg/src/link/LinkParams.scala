// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg.link

import chisel3._
import chisel3.util._

/** TileLink TL-UL opcodes, verbatim on the wire so a Chipyard adapter need not translate.
  * A and D opcodes overlap; the header's `chan` bit tells them apart: downstream M is A-type and
  * V is D-type, upstream the reverse.
  */
object TLOpcode {
  // A channel
  val PutFullData    = 0.U(3.W)
  val PutPartialData = 1.U(3.W)
  val Get            = 4.U(3.W)
  // D channel
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
  * @param w             Data lanes per direction: 16, or 8 (two beats per 16-bit flit).
  * @param narrowCapable Build the runtime 16 -> 8 lane mux on the `link_narrow` strap, a recovery
  *                      path silicon can take. Narrow ties d[15:8] low and computes parity over the
  *                      active lanes only, so a stuck-high unused lane is not mistaken for data.
  * @param creditDepth   Outstanding A packets per direction; must not exceed what the receiver
  *                      buffers. Both adapters hold one request, so 1.
  * @param maxBurstLog2  Largest gpuMem burst, log2 so the packet length follows from the header.
  * @param divLog2       Beat-rate divider at reset, log2; the `link_fast` strap selects 0.
  * @param gapBeats      Idle beats between packets, at least 1: the receiver resynchronizes there.
  * @param trainBeats    Consecutive good training beats required before `link_up`.
  */
case class LinkParams(
    w: Int = 16,
    creditDepth: Int = 1,
    maxBurstLog2: Int = 4,
    divLog2: Int = 1,
    gapBeats: Int = 1,
    trainBeats: Int = 16,
    narrowCapable: Boolean = false
) {
  require(w == 16 || w == 8, s"w must be 16 (normal) or 8 (link_narrow), got $w")
  require(!narrowCapable || w == 16,
          "narrowCapable halves 16 physical lanes to 8; it is meaningless at w=8")
  require(creditDepth >= 1, "creditDepth must be at least 1")
  require(maxBurstLog2 >= 0 && maxBurstLog2 <= 6, "gpuMem wlen is 7 bits, so log2 ≤ 6")
  require(gapBeats >= 1, "gapBeats must be ≥1 -- it is the resynchronization point")

  val flitBits: Int = 16
  val beatsPerFlit: Int = flitBits / w

  val maxBurst: Int = 1 << maxBurstLog2
  val maxPacketFlits: Int = 3 + maxBurst   // a V.A write

  val divCycles: Int = 1 << divLog2
}
