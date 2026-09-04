# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: Apache-2.0
"""Host-side BorgLinkMaster model, driven through chip_top's real pads.

This is the counterpart to hardware/borg/src/link/ -- it speaks the wire
protocol from Python so the ASIC's own BorgLinkSlave, padring and lane map can
be exercised end to end. Everything here mirrors the RTL rather than
reimplementing it loosely; the places that matter:

  * Beats advance on a clock *enable*, not a divided clock (BorgLinkClockGen),
    so a beat is every ``divCycles`` core clocks. The master free-runs the
    phase and simply defines it.
  * Parity is ODD over {d, v}, checked on idle beats too. That is deliberate:
    an unplugged or stuck-low cable reads d=0,v=0,p=0, which is even, so it
    fails every beat and link_up can never assert (LinkFlit's doc).
  * Training is a word that inverts every beat, giving the slave a transition
    to lock onto. It needs ``trainBeats`` good transitions before link_up.
  * Credit returns are TOGGLE-encoded, not pulsed -- a one-beat pulse at the
    beat rate can be missed or double-counted by a receiver sampling at the
    core rate (CreditCounter's doc).

Lane map is BorgOnlyTop's, and validating it is the whole point of this test:
a mistake here is unfixable after tapeout.
"""

from cocotb.triggers import RisingEdge

# --- BorgOnlyTop lane map (asic/tt/src/BorgOnlyTop.scala) -------------------
DN_D_LO, DN_D_HI = 0, 15      # in  (we drive)
DN_V            = 16          # in
DN_P            = 17          # in
DN_CRED         = 18          # out (chip drives)
UP_D_LO, UP_D_HI = 19, 34     # out
UP_V            = 35          # out
UP_P            = 36          # out
UP_CRED         = 37          # in  (we drive)
LINK_UP         = 38          # out
LINK_ERR        = 39          # out
DBG_O_LO, DBG_O_HI = 40, 45   # out (tied 0)

NUM_BIDIR = 46

# input_PAD map
DBG_SEL_LO, DBG_SEL_HI = 0, 1
LINK_NARROW = 2
LINK_FAST   = 3

# --- Protocol constants (LinkParams / LinkFlit / TLOpcode) ------------------
W            = 16
DIV_CYCLES   = 2       # divLog2 = 1
TRAIN_BEATS  = 16
GAP_BEATS    = 1

CHAN_M, CHAN_V = 0, 1
OP_PUT_FULL, OP_PUT_PARTIAL, OP_GET = 0, 1, 4
OP_ACCESS_ACK, OP_ACCESS_ACK_DATA = 0, 1

TRAIN_WORD = 0xA5A5    # Fill(w/8, 0xA5)


def odd_parity(d: int, v: int, width: int = W) -> int:
    """p = !(Cat(d, v).xorR) -- makes the total ones in {d, v, p} odd.

    `width` is the ACTIVE lane count: narrow mode ties d[15:8] low and excludes
    it, so folding the dead half in here would disagree with the RTL.
    """
    bits = bin(d & ((1 << width) - 1)).count("1") + (v & 1)
    return 0 if (bits & 1) else 1


def mmio_payload(size: int, addr: int) -> int:
    return ((size & 0x3) << 10) | (addr & 0x3FF)


def header(chan: int, opcode: int, payload: int) -> int:
    return ((chan & 1) << 15) | ((opcode & 0x7) << 12) | (payload & 0xFFF)


class LinkMaster:
    """Drives the DN lanes and samples the UP lanes through the real pads."""

    def __init__(self, dut, log, narrow=False):
        self.dut = dut
        self.log = log
        # link_narrow: drive d[7:0] only, two beats per flit (LSB slice first),
        # d[15:8] tied low, parity over the live lanes. Mirrors the RTL's
        # narrowCapable mux -- the post-silicon recovery mode.
        self.narrow = narrow
        self.aw = 8 if narrow else W       # active lane count
        self.dn_d = TRAIN_WORD
        self.dn_v = 1
        self.up_cred_level = 0     # toggle we drive to return UP credits
        self.dn_cred_seen = None   # last dn_cred level sampled
        self.credits = 1           # creditDepth
        self.parity_errors = 0
        self.beats = 0
        self.check_parity = False   # armed once link_up asserts
        self.trace = False          # log every chip-driven beat

        # We own exactly the chip's input lanes; everything else stays released
        # so a wiring mistake surfaces as a bus conflict, not a masked value.
        oe = 0
        for i in range(DN_D_LO, DN_D_HI + 1):
            oe |= 1 << i
        oe |= (1 << DN_V) | (1 << DN_P) | (1 << UP_CRED)
        self.drv_oe = oe

    # -- pin level ----------------------------------------------------------
    def _apply(self):
        v = 0
        d = self.dn_d & ((1 << self.aw) - 1)
        v |= d << DN_D_LO
        v |= (self.dn_v & 1) << DN_V
        v |= odd_parity(d, self.dn_v, self.aw) << DN_P
        v |= (self.up_cred_level & 1) << UP_CRED
        self.dut.drv.value = v
        self.dut.drv_oe.value = self.drv_oe

    def _sample(self):
        """Read the chip-driven lanes. Returns (d, v, p, link_up, link_err)."""
        # Index via the string form: it is MSB-first by definition, so bit i is
        # at position NUM_BIDIR-1-i regardless of how cocotb maps a declared
        # [45:0] range onto __getitem__ (which differs between versions and is
        # exactly the kind of off-by-reversal that silently reads the wrong
        # lane). It also preserves x/z instead of poisoning an int conversion.
        raw = str(self.dut.bidir_PAD.value)

        def bit(i):
            c = raw[NUM_BIDIR - 1 - i]
            return int(c) if c in ("0", "1") else None

        d = 0
        for i in range(UP_D_LO, UP_D_HI + 1):
            b = bit(i)
            if b is None:
                return None
            d |= b << (i - UP_D_LO)
        vals = [bit(UP_V), bit(UP_P), bit(LINK_UP), bit(LINK_ERR)]
        if any(x is None for x in vals):
            return None
        return (d, vals[0], vals[1], vals[2], vals[3])

    async def beat(self):
        """Advance exactly one beat (divCycles core clocks), pins registered."""
        self._apply()
        for _ in range(DIV_CYCLES):
            await RisingEdge(self.dut.clk)
        self.beats += 1

        s = self._sample()
        if s is not None:
            d, v, p, _up, _err = s
            raw = str(self.dut.bidir_PAD.value)

            def bit(i):
                c = raw[NUM_BIDIR - 1 - i]
                return int(c) if c in ("0", "1") else None
            # Parity is checked on every beat, idle included -- that is what
            # makes a dead cable detectable at all.
            if self.check_parity and odd_parity(d, v, self.aw) != p:
                self.parity_errors += 1
                self.log.warning("parity error beat %d: d=0x%04x v=%d p=%d",
                                 self.beats, d, v, p)
            if self.trace and (v or _up or _err):
                self.log.info("beat %d: up_d=0x%04x v=%d p=%d link_up=%d err=%d cred=%d",
                              self.beats, d, v, p, _up, _err, bit(DN_CRED))
            # Credit returns are toggle-encoded.
            cred = bit(DN_CRED)
            if cred is not None and self.dn_cred_seen is not None and cred != self.dn_cred_seen:
                self.credits += 1
            self.dn_cred_seen = cred
        return s

    def read_dbg(self):
        """Sample dbg_o[5:0]. Returns None if any lane is x/z."""
        raw = str(self.dut.bidir_PAD.value)
        v = 0
        for i in range(DBG_O_LO, DBG_O_HI + 1):
            c = raw[NUM_BIDIR - 1 - i]
            if c not in ("0", "1"):
                return None
            v |= int(c) << (i - DBG_O_LO)
        return v

    # -- link bring-up ------------------------------------------------------
    async def train(self, max_beats=400):
        """Send the inverting training word until the slave raises link_up."""
        self.dn_v = 1
        for _ in range(max_beats):
            s = await self.beat()
            self.dn_d = (~self.dn_d) & ((1 << self.aw) - 1)  # live lanes only
            if s is not None and s[3] == 1:
                self.log.info("link_up asserted after %d beats", self.beats)
                self.check_parity = True
                return True
        return False

    async def idle(self, beats=1):
        """Idle beats: v=0, but parity still valid."""
        self.dn_v = 0
        self.dn_d = 0
        for _ in range(beats):
            await self.beat()

    # -- packets ------------------------------------------------------------
    async def send_flits(self, flits):
        """Send one atomic packet, then the mandatory inter-packet gap."""
        self.dn_v = 1
        for f in flits:
            if self.narrow:
                # LSB slice first, matching LinkTx's serialization order.
                self.dn_d = f & 0xFF
                await self.beat()
                self.dn_d = (f >> 8) & 0xFF
                await self.beat()
            else:
                self.dn_d = f & 0xFFFF
                await self.beat()
        await self.idle(GAP_BEATS)

    async def write32(self, addr, data, size=2, timeout_beats=200):
        """M.A PutFullData: header + two data flits (LinkFlit.flitsDn).

        Waits for the M.D AccessAck. creditDepth is 1 -- single outstanding by
        construction -- so the ack must be collected (and its credit returned)
        before the next request, or framing and credits drift and the slave
        eventually has nothing to answer with.
        """
        h = header(CHAN_M, OP_PUT_FULL, mmio_payload(size, addr))
        await self.send_flits([h, data & 0xFFFF, (data >> 16) & 0xFFFF])
        return await self.recv_response(timeout_beats)

    async def read32(self, addr, size=2, timeout_beats=200):
        """M.A Get (header only), then collect the M.D AccessAckData reply."""
        h = header(CHAN_M, OP_GET, mmio_payload(size, addr))
        await self.send_flits([h])
        return await self.recv_response(timeout_beats)

    async def recv_response(self, timeout_beats=200):
        """Collect one UP packet; returns its 32-bit data, or None on timeout.

        UP carries V.A (Borg's gpuMem requests) and M.D (our MMIO replies). We
        only expect M.D here, but a V.A would mean Borg is fetching, which is
        worth reporting rather than silently dropping.
        """
        got = []
        expect = None
        half = None          # narrow: low slice awaiting its high slice
        for _ in range(timeout_beats):
            s = await self.beat()
            if s is None:
                continue
            d, v, p, _up, _err = s
            if not v:
                continue
            if self.narrow:
                # Two beats per flit, LSB slice first (LinkTx's order).
                if half is None:
                    half = d & 0xFF
                    continue
                d = ((d & 0xFF) << 8) | half
                half = None
            got.append(d)
            if expect is None:
                hdr = got[0]
                chan = (hdr >> 15) & 1
                op = (hdr >> 12) & 0x7
                if chan == CHAN_V:
                    self.log.warning("unexpected V.A (gpuMem) packet: 0x%04x", hdr)
                    expect = 2
                else:
                    expect = 3 if op == OP_ACCESS_ACK_DATA else 1
            if len(got) >= expect:
                # Return the credit we just consumed (toggle-encoded).
                self.up_cred_level ^= 1
                if expect == 3:
                    return got[1] | (got[2] << 16)
                return 0
        return None
