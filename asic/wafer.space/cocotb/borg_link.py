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

Lane maps are BorgOnlyTop's, one per slot, and validating them is the whole
point of this test: a mistake here is unfixable after tapeout.
"""

from cocotb.triggers import RisingEdge

# --- BorgOnlyTop lane maps (asic/wafer/src/BorgOnlyTop.scala) ---------------
class LaneMap:
    """Pad positions of every link lane for one wafer.space slot.

    `dn_d_bidir` / `dn_d_input` list, per dn_d bit (index = bit), the bidir or
    input_PAD position carrying it; exactly one of the two holds each bit.
    """

    def __init__(self, name, num_bidir, num_input, dn_d, dn_v, dn_p, dn_cred,
                 up_d_lo, up_v, up_p, up_cred, link_up, link_err, dbg_o_lo):
        self.name = name
        self.num_bidir = num_bidir
        self.num_input = num_input
        self.dn_d = dn_d              # list of ("bidir"|"input", pad) per bit
        self.dn_v, self.dn_p, self.dn_cred = dn_v, dn_p, dn_cred
        self.up_d = list(range(up_d_lo, up_d_lo + 16))
        self.up_v, self.up_p, self.up_cred = up_v, up_p, up_cred
        self.link_up, self.link_err = link_up, link_err
        self.dbg_o = list(range(dbg_o_lo, dbg_o_lo + 6))


# 1x0.5: 46 bidir + 4 input. Every link lane on bidir.
MAP_1X0P5 = LaneMap(
    "1x0p5", 46, 4,
    dn_d=[("bidir", i) for i in range(16)],
    dn_v=16, dn_p=17, dn_cred=18, up_d_lo=19, up_v=35, up_p=36, up_cred=37,
    link_up=38, link_err=39, dbg_o_lo=40)

# 1x1: 40 bidir + 12 input. dn_d[7:0] moves onto input_PAD[11:4],
# dn_d[15:8] sits on bidir[7:0]; bidir[38:39] are spare.
MAP_1X1 = LaneMap(
    "1x1", 40, 12,
    dn_d=[("input", 4 + i) for i in range(8)] + [("bidir", i) for i in range(8)],
    dn_v=8, dn_p=9, dn_cred=10, up_d_lo=11, up_v=27, up_p=28, up_cred=29,
    link_up=30, link_err=31, dbg_o_lo=32)

LANE_MAPS = {"1x0p5": MAP_1X0P5, "1x1": MAP_1X1}

# input_PAD straps: identical in both slots
DBG_SEL_LO, DBG_SEL_HI = 0, 1
LINK_NARROW = 2
LINK_FAST   = 3


def straps(link_fast=0, link_narrow=0, dbg_sel=0):
    return ((dbg_sel & 0x3) << DBG_SEL_LO
            | (link_narrow & 1) << LINK_NARROW
            | (link_fast & 1) << LINK_FAST)


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

    def __init__(self, dut, log, lanes, narrow=False, strap_bits=0):
        self.dut = dut
        self.log = log
        self.lanes = lanes
        self.strap_bits = strap_bits   # input_PAD[3:0]; set before reset
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
        # UP receive: assembled on EVERY beat, including while we transmit --
        # the link is full duplex, so a packet the chip sends while we are busy
        # sending must not be lost.
        self.rx_flits = []          # packet being assembled
        self.rx_half = None         # narrow: low slice awaiting its high slice
        self.rx_queue = []          # completed UP packets, oldest first
        # Far-side DRAM, as MemoryController stores it: one 16-bit halfword
        # per byte address (even), a write word being one halfword.
        self.mem = {}
        self.va_log = []            # (op, addr, words) per served V.A

        # We own exactly the chip's input lanes; everything else stays released
        # so a wiring mistake surfaces as a bus conflict, not a masked value.
        oe = 0
        for kind, pad in lanes.dn_d:
            if kind == "bidir":
                oe |= 1 << pad
        oe |= (1 << lanes.dn_v) | (1 << lanes.dn_p) | (1 << lanes.up_cred)
        self.drv_oe = oe

    # -- pin level ----------------------------------------------------------
    def _apply(self):
        L = self.lanes
        d = self.dn_d & ((1 << self.aw) - 1)
        v = 0
        inp = self.strap_bits & 0xF
        for bit, (kind, pad) in enumerate(L.dn_d):
            b = (d >> bit) & 1
            if kind == "bidir":
                v |= b << pad
            else:
                inp |= b << pad
        v |= (self.dn_v & 1) << L.dn_v
        v |= odd_parity(d, self.dn_v, self.aw) << L.dn_p
        v |= (self.up_cred_level & 1) << L.up_cred
        self.dut.drv.value = v
        self.dut.drv_oe.value = self.drv_oe
        self.dut.input_drv.value = inp

    def _bit(self, raw, i):
        c = raw[self.lanes.num_bidir - 1 - i]
        return int(c) if c in ("0", "1") else None

    def _sample(self):
        """Read the chip-driven lanes. Returns (d, v, p, link_up, link_err)."""
        # Index via the string form: it is MSB-first by definition, so bit i is
        # at position num_bidir-1-i regardless of how cocotb maps a declared
        # range onto __getitem__ (which differs between versions and is
        # exactly the kind of off-by-reversal that silently reads the wrong
        # lane). It also preserves x/z instead of poisoning an int conversion.
        L = self.lanes
        raw = str(self.dut.bidir_PAD.value)
        d = 0
        for i, pad in enumerate(L.up_d):
            b = self._bit(raw, pad)
            if b is None:
                return None
            d |= b << i
        vals = [self._bit(raw, x) for x in (L.up_v, L.up_p, L.link_up, L.link_err)]
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
            # Parity is checked on every beat, idle included -- that is what
            # makes a dead cable detectable at all.
            if self.check_parity and odd_parity(d, v, self.aw) != p:
                self.parity_errors += 1
                self.log.warning("parity error beat %d: d=0x%04x v=%d p=%d",
                                 self.beats, d, v, p)
            if self.trace == "beats" and (v or _up or _err or self.dn_v):
                self.log.warning("beat %d: dn_d=0x%04x dn_v=%d | up_d=0x%04x v=%d p=%d link_up=%d err=%d cred=%d",
                              self.beats, self.dn_d, self.dn_v, d, v, p, _up, _err, self._bit(raw, self.lanes.dn_cred))
            # Credit returns are toggle-encoded.
            cred = self._bit(raw, self.lanes.dn_cred)
            if cred is not None and self.dn_cred_seen is not None and cred != self.dn_cred_seen:
                self.credits += 1
            self.dn_cred_seen = cred
            if self.check_parity and v:
                self._rx_flit(d)
        return s

    def _rx_flit(self, d):
        if self.narrow:
            # Two beats per flit, LSB slice first (LinkTx's order).
            if self.rx_half is None:
                self.rx_half = d & 0xFF
                return
            d = ((d & 0xFF) << 8) | self.rx_half
            self.rx_half = None
        self.rx_flits.append(d)
        hdr = self.rx_flits[0]
        chan, op = (hdr >> 15) & 1, (hdr >> 12) & 0x7
        if chan == CHAN_V:
            # V.A: header + addr[15:0], then 2^wlenLog2 words on a write.
            expect = 2 if op == OP_GET else 2 + (1 << ((hdr >> 9) & 0x7))
        else:
            expect = 3 if op == OP_ACCESS_ACK_DATA else 1
        if len(self.rx_flits) >= expect:
            if self.trace:
                self.log.warning("rx pkt beat %d: %s", self.beats,
                                 " ".join(f"{f:04x}" for f in self.rx_flits))
            self.rx_queue.append(self.rx_flits)
            self.rx_flits = []

    def word(self, addr):
        """The 32-bit DRAM word at `addr`, as a word read reassembles it."""
        return (self.mem.get(addr + 2, 0) << 16) | self.mem.get(addr, 0)

    async def serve_va(self, pkt):
        """Answer one V.A (Borg gpuMem request) from the halfword DRAM."""
        hdr = pkt[0]
        op = (hdr >> 12) & 0x7
        addr = ((hdr & 0x1FF) << 16) | pkt[1]
        if op == OP_GET:
            data = self.word(addr)
            self.va_log.append(("get", addr, [data]))
            await self.send_flits([header(CHAN_V, OP_ACCESS_ACK_DATA, 0),
                                   data & 0xFFFF, (data >> 16) & 0xFFFF])
        else:
            words = pkt[2:]
            for i, w in enumerate(words):
                self.mem[addr + 2 * i] = w
            self.va_log.append(("put", addr, words))
            await self.send_flits([header(CHAN_V, OP_ACCESS_ACK, 0)])
        # Only V.A consumes UP credit (M.D replies are credited on the DN side
        # by the chip), and BorgLinkMaster returns it once the V.D response
        # is out -- toggle-encoded. Returning credit for M.D as well inflates
        # the chip's CreditCounter until it wraps and stalls its transmitter
        # mid-packet.
        self.up_cred_level ^= 1

    def read_dbg(self):
        """Sample dbg_o[5:0]. Returns None if any lane is x/z."""
        raw = str(self.dut.bidir_PAD.value)
        v = 0
        for i, pad in enumerate(self.lanes.dbg_o):
            b = self._bit(raw, pad)
            if b is None:
                return None
            v |= b << i
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
        """Wait for one M.D reply; returns its 32-bit data (0 for a write ack),
        or None on timeout. V.A requests arriving meanwhile -- Borg reading or
        writing DRAM while it runs -- are served from `mem` on the way.
        """
        for _ in range(timeout_beats):
            while self.rx_queue:
                pkt = self.rx_queue.pop(0)
                if (pkt[0] >> 15) & 1 == CHAN_V:
                    await self.serve_va(pkt)
                    continue
                if ((pkt[0] >> 12) & 0x7) == OP_ACCESS_ACK_DATA:
                    return pkt[1] | (pkt[2] << 16)
                return 0
            await self.beat()
        return None

    async def serve(self, beats):
        """Idle for `beats`, serving any V.A that arrives."""
        for _ in range(beats):
            while self.rx_queue:
                pkt = self.rx_queue.pop(0)
                if (pkt[0] >> 15) & 1 == CHAN_V:
                    await self.serve_va(pkt)
                else:
                    self.log.warning("unexpected M.D packet: 0x%04x", pkt[0])
            await self.idle(1)
