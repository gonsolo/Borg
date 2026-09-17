# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: Apache-2.0
"""Pad-level tests for the wafer.space Borg-only bridge (plan Phase 5, test 8).

Runs the link protocol against chip_top through the real gf180mcu pads, so
this covers the three things no FPGA rung can: the padring, the true w=16 lane
map, and (with GL=1) the post-P&R netlist. The lane map in particular is
unfixable after tapeout -- moving a pin is a padring change -- so it is worth
checking by construction rather than by inspection.

Runs against either slot's lane map (SLOT=1x1, the tapeout slot, or 1x0p5).

  make sim-link       # RTL
  make sim-link-gl    # gate level, after copy-final
"""

import os
import logging
from pathlib import Path

import cocotb
from cocotb.triggers import Timer, ClockCycles
from cocotb.clock import Clock
from cocotb_tools.runner import get_runner

import sys

import borg_link
from borg_link import LinkMaster

# RDL-generated register offsets (`make rdl`): the single source of truth.
sys.path.insert(0, str(Path(__file__).resolve().parent / "../../../out/hardware/borg/rdl"))
import borg_mmio as regs  # noqa: E402

sim = os.getenv("SIM", "icarus")
gl = os.getenv("GL", False)
# Gate level runs either the post-layout powered netlist (the default, after
# copy-final) or any other netlist named here -- e.g. LibreLane's synthesis
# output, `make sim-link-synth`, available hours before signoff finishes.
gl_netlist = Path(os.getenv(
    "GL_NETLIST", Path(__file__).resolve().parent / "../final/pnl/chip_top.pnl.v"))
powered = bool(gl) and ".pnl." in gl_netlist.name
pdk_root = os.getenv("PDK_ROOT", Path(__file__).resolve().parent / "../gf180mcu")
pdk = os.getenv("PDK", "gf180mcuD")
scl = os.getenv("SCL", "gf180mcu_fd_sc_mcu7t5v0")
pad = os.getenv("PAD", "gf180mcu_fd_io")
sram = os.getenv("SRAM", "gf180mcu_fd_ip_sram")
slot = os.getenv("SLOT", "1x1")
LANES = borg_link.LANE_MAPS[slot]

hdl_toplevel = "chip_link_tb"


async def start_up(dut, link_fast=0, link_narrow=0, dbg_sel=0):
    """Power, straps, clock, reset -- straps must be stable before reset."""
    if powered:
        dut.VDD.value = 1
        dut.VSS.value = 0
        dut.DVDD.value = 1
        dut.DVSS.value = 0

    strap_bits = borg_link.straps(link_fast, link_narrow, dbg_sel)
    dut.input_drv.value = strap_bits
    dut.drv.value = 0
    dut.drv_oe.value = 0

    cocotb.start_soon(Clock(dut.clk, 40, "ns").start())  # 25 MHz

    dut.rst_n.value = 0
    await Timer(1000, "ns")
    dut.rst_n.value = 1
    await ClockCycles(dut.clk, 10)
    return strap_bits


@cocotb.test()
async def test_link_trains(dut):
    """The slave must reach link_up from the master's training pattern.

    Passing this already exercises a lot of the lane map: the training word
    only decodes if dn_d[15:0], dn_v and dn_p are all mapped correctly (parity
    is checked every beat), and link_up must come back on the right pad.
    """
    log = logging.getLogger("link")
    m = LinkMaster(dut, log, LANES, strap_bits=await start_up(dut))

    assert await m.train(), (
        "link_up never asserted -- the slave saw no valid training transitions. "
        "Suspect the dn_d/dn_v/dn_p lane mapping, the parity polarity, or the "
        "beat rate (link_fast strap)."
    )
    assert m.parity_errors == 0, f"{m.parity_errors} parity errors during training"


@cocotb.test()
async def test_link_err_stays_low(dut):
    """A well-formed idle line must not raise link_err."""
    log = logging.getLogger("link")
    m = LinkMaster(dut, log, LANES, strap_bits=await start_up(dut))
    assert await m.train(), "link_up never asserted"

    await m.idle(50)
    s = m._sample()
    assert s is not None, "chip outputs are x/z -- check bidir_oe on the output lanes"
    assert s[4] == 0, "link_err asserted on a clean idle line"


@cocotb.test()
async def test_mmio_roundtrip(dut):
    """Write a Borg GPR through the pads and read it back.

    This is the real lane-map check: it only passes if BOTH directions are
    mapped correctly end to end -- our header/data out on dn_*, the slave's
    reply back on up_*, and the credit toggles in between. gpr[] is sw=rw
    (imem is write-only, so it cannot serve as a read-back target).
    """
    log = logging.getLogger("link")
    m = LinkMaster(dut, log, LANES, strap_bits=await start_up(dut))
    assert await m.train(), "link_up never asserted"
    await m.idle(4)

    # r30/r31 are the pixel-coordinate pseudo-registers (BorgLane's
    # resolveCoordReg substitutes coordX/coordY on every read port, MMIO
    # included), so they are not storage and cannot round-trip. Everything
    # below them is a real GPR.
    # Full 32-bit patterns with both halves distinct: GPRs are FP32, and a
    # lane or flit that dropped the upper half must not pass.
    bad = []
    for reg in (0, 1, 2, 7, 8, 15, 16, 23, 24, 29):
        val = 0xC0490000 | (0x0FD0 + reg)
        addr = reg * 4                      # gpr[] @ 0x000, stride 4
        await m.write32(addr, val)
        await m.idle(4)
        got = await m.read32(addr, timeout_beats=120)
        if got is None:
            bad.append((reg, "no response"))
        elif got != val:
            bad.append((reg, f"got 0x{got:08x} want 0x{val:08x}"))
        await m.idle(4)
    log.warning("gpr probe failures: %s", bad if bad else "none")
    assert not bad, f"gpr round-trip failures: {bad}"

    assert m.parity_errors == 0, f"{m.parity_errors} parity errors"


@cocotb.test()
async def test_narrow_strap_round_trip(dut):
    """The link_narrow strap must actually halve the lanes, in silicon.

    This is the post-silicon recovery path: ASIC pins cannot be re-synthesized
    after tapeout, so an elaboration-time w=8 would only be a different build,
    useless to a fabricated part. Strapping input_PAD[2] high must make the SAME
    chip train and carry traffic on d[7:0] alone, two beats per flit.
    """
    log = logging.getLogger("link")
    m = LinkMaster(dut, log, LANES, narrow=True,
                   strap_bits=await start_up(dut, link_narrow=1))

    assert await m.train(), (
        "link_up never asserted with link_narrow strapped -- the runtime "
        "w=16->w=8 mux is not engaging (LinkParams.narrowCapable)."
    )
    await m.idle(4)

    for reg, val in ((0, 0x3F803C00), (7, 0xBEEF0FF0), (29, 0x12345A5A)):
        addr = reg * 4
        await m.write32(addr, val)
        await m.idle(4)
        got = await m.read32(addr)
        assert got is not None, f"no response to narrow read of gpr[{reg}]"
        assert got == val, (
            f"narrow gpr[{reg}]: wrote 0x{val:08x}, read back 0x{got:08x}"
        )
    assert m.parity_errors == 0, f"{m.parity_errors} parity errors in narrow mode"


@cocotb.test()
async def test_debug_bus(dut):
    """dbg_o must carry real state, selected by dbg_sel.

    These six pads were tied to zero. That matters because if a fabricated part
    never trains, the only other evidence on the package is link_err -- two bits
    for a fault with many indistinguishable causes. The strap view is checked
    here because it validates the whole path at once: the dbg_sel mux, the
    dbg_o lanes, and the padring positions of both.
    """
    log = logging.getLogger("link")
    m = LinkMaster(dut, log, LANES,
                   strap_bits=await start_up(dut, link_fast=0, link_narrow=1, dbg_sel=3))

    # View 3 = {heartbeat[5], link_fast, link_narrow, dbg_sel[1:0], link_up}
    v = m.read_dbg()
    assert v is not None, f"dbg_o reads x/z -- check bidir_oe on lanes {LANES.dbg_o}"
    assert (v >> 4) & 1 == 0, f"dbg_o link_fast bit wrong: 0x{v:02x}"
    assert (v >> 3) & 1 == 1, f"dbg_o link_narrow bit wrong (strapped 1): 0x{v:02x}"
    assert (v >> 1) & 3 == 3, f"dbg_o should read back dbg_sel=3: 0x{v:02x}"

    # The heartbeat is the "is the clock even arriving" bit: it must toggle on
    # its own, with no traffic and without the link up.
    seen = set()
    for _ in range(160):
        d = m.read_dbg()
        if d is not None:
            seen.add((d >> 5) & 1)
        await ClockCycles(dut.clk, 1)
    assert seen == {0, 1}, f"dbg_o heartbeat never toggled (saw {seen}) -- a dead clock would look like this"

    # View 0's link_up bit must agree with the link_up pad once trained.
    m.strap_bits = 0                             # dbg_sel = 0
    m._apply()
    await ClockCycles(dut.clk, 4)
    assert await m.train(), "link_up never asserted"
    v0 = m.read_dbg()
    assert v0 is not None and (v0 >> 4) & 1 == 1, (
        f"dbg_o view 0 link_up disagrees with the link_up pad: 0x{v0:02x}"
    )
    log.info("debug bus: strap view + heartbeat + bring-up view all consistent")


# software/borg/borg_isa.h's BORG_INSTR_LOAD / BORG_INSTR_STORE
def instr_load(rs1, rd):
    return 0x44000000 | (rs1 << 15) | (rd << 7)


def instr_store(rs1, rs2):
    return 0x48000000 | (rs2 << 20) | (rs1 << 15)


@cocotb.test()
async def test_store_load_32bit(dut):
    """A shader STORE of a full FP32 word must reach DRAM intact over the pads.

    DRAM is on the far side of the link, and every write word crossing it is
    one 16-bit flit, so Borg has to send a 32-bit value as a two-word burst.
    A single-word write dropped the upper half: -pi arrived as 0x00000FDB and
    LOADed back as garbage. -pi goes first because its low halfword is
    non-zero, which tells a dropped upper half apart from a missing write.
    """
    log = logging.getLogger("link")
    m = LinkMaster(dut, log, LANES, strap_bits=await start_up(dut))
    assert await m.train(), "link_up never asserted"
    await m.idle(4)

    m.trace = os.getenv("LINK_TRACE", "")
    ls_base = 0x1000
    assert await m.write32(regs.BORG_LS_BASE_OFFSET, ls_base) is not None
    for i, val in enumerate((0xC0490FDB, 0x3F800000)):   # -pi, 1.0f
        index = 3 + i
        for addr, data in (
            (regs.BORG_GPR_OFFSET + 0 * 4, index),
            (regs.BORG_GPR_OFFSET + 1 * 4, val),
            (regs.BORG_GPR_OFFSET + 3 * 4, 0),
            (regs.BORG_IMEM_OFFSET + 0 * 4, instr_store(rs1=0, rs2=1)),
            (regs.BORG_IMEM_OFFSET + 1 * 4, instr_load(rs1=0, rd=3)),
            (regs.BORG_IMEM_OFFSET + 2 * 4, 0),
            (regs.BORG_CONTROL_OFFSET, 2),      # reset pipeline
            (regs.BORG_CONTROL_OFFSET, 1),      # start
        ):
            assert await m.write32(addr, data, timeout_beats=2000) is not None, \
                f"no ack for write to 0x{addr:x}"

        for _ in range(100):
            status = await m.read32(regs.BORG_STATUS_OFFSET, timeout_beats=2000)
            assert status is not None, "no response to status read"
            if status & 2:
                break
            await m.serve(20)
        else:
            raise AssertionError("shader never went idle")

        addr = ls_base + index * 4
        stored = m.word(addr)
        loaded = await m.read32(regs.BORG_GPR_OFFSET + 3 * 4, timeout_beats=2000)
        log.info("0x%08x: DRAM[0x%x] = 0x%08x, r3 = 0x%08x, V.A: %s",
                 val, addr, stored, loaded, m.va_log)
        assert stored == val, f"DRAM[0x{addr:x}] = 0x{stored:08x}, want 0x{val:08x}"
        assert loaded == val, f"LOAD read back 0x{loaded:08x}, want 0x{val:08x}"
        m.va_log.clear()
    assert m.parity_errors == 0, f"{m.parity_errors} parity errors"


# --- Borg shader checks through the pads ------------------------------------
# Ported from test/soc/user_peripherals/borg/test.py (the QSPI CPU SoC
# suite), driven over the link instead of a CPU bus. The chip is FP32.

import json
import math
import struct


def f32_bits(x):
    return struct.unpack("<I", struct.pack("<f", x))[0]


def f32(x):
    """x rounded to float32, as the chip will hold it."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


def bits_f32(b):
    return struct.unpack("<f", struct.pack("<I", b & 0xFFFFFFFF))[0]


def close32(actual, expected):
    return abs(actual - expected) < max(1e-6 * abs(expected), 1e-6)


class LinkBorg:
    """Run shader programs on the chip through a trained LinkMaster."""

    def __init__(self, m):
        self.m = m

    async def w(self, addr, data):
        assert await self.m.write32(addr, data, timeout_beats=2000) is not None, \
            f"no ack for write to 0x{addr:x}"

    async def r(self, addr):
        v = await self.m.read32(addr, timeout_beats=2000)
        assert v is not None, f"no response to read of 0x{addr:x}"
        return v

    async def run(self, program, operands, polls=100):
        """Reset PC, load `program` + halt and float operands, run to idle."""
        await self.w(regs.BORG_CONTROL_OFFSET, 2)
        for reg, val in operands:
            await self.w(regs.BORG_GPR_OFFSET + reg * 4, f32_bits(val))
        for i, instr in enumerate(list(program) + [regs.encode_rv32_halt()]):
            await self.w(regs.BORG_IMEM_OFFSET + i * 4, instr)
        await self.w(regs.BORG_CONTROL_OFFSET, 2)
        await self.w(regs.BORG_CONTROL_OFFSET, 1)
        for _ in range(polls):
            if await self.r(regs.BORG_STATUS_OFFSET) & 2:
                return
            await self.m.serve(20)
        raise AssertionError("shader never went idle")

    async def reg(self, idx):
        return bits_f32(await self.r(regs.BORG_GPR_OFFSET + idx * 4))


async def trained_borg(dut):
    m = LinkMaster(dut, logging.getLogger("link"), LANES, strap_bits=await start_up(dut))
    assert await m.train(), "link_up never asserted"
    await m.idle(4)
    return LinkBorg(m)


@cocotb.test()
async def test_shader_math(dut):
    """ADD / MUL / FNEG / FMA over the shared vectors in data/test_cases.json."""
    b = await trained_borg(dut)
    vectors = Path(__file__).resolve().parent / "../../../data/test_cases.json"
    pairs = json.loads(vectors.read_text())["pairs"]
    for a, c0 in pairs:
        a, c0 = f32(a), f32(c0)
        cases = [
            ("add", [regs.encode_rv32_fadd()], [(0, a), (1, c0)], 2, f32(a + c0)),
            ("mul", [regs.encode_rv32_fmul()], [(0, a), (1, c0)], 2, f32(a * c0)),
            ("fneg", [regs.encode_rv32_fneg()], [(0, a)], 1, -a),
        ] + [
            ("fma", [regs.encode_rv32_fmadd()], [(0, a), (1, c0), (3, f32(k))], 2,
             f32(a * c0 + f32(k)))
            for k in (1.0, -0.5)
        ]
        for op, prog, ops, rd, want in cases:
            await b.run(prog, ops)
            got = await b.reg(rd)
            assert close32(got, want), f"{op}{[v for _, v in ops]} = {got}, want {want}"
    assert b.m.parity_errors == 0


@cocotb.test()
async def test_rotation_shader(dut):
    """The 4-instruction rotation shader (borg_rotate.c)."""
    b = await trained_borg(dut)
    prog = [
        regs.encode_rv32_fmul(rs1=2, rs2=3, rd=0),           # cos*x
        regs.encode_rv32_fmadd(rs1=4, rs2=6, rs3=0, rd=0),   # -sin*y + cos*x
        regs.encode_rv32_fmul(rs1=5, rs2=3, rd=1),           # sin*x
        regs.encode_rv32_fmadd(rs1=2, rs2=6, rs3=1, rd=1),   # cos*y + sin*x
    ]
    c45 = f32(math.cos(math.pi / 4))
    for cos, sin, x, y in ((1.0, 0.0, 1.0, 0.0), (c45, c45, 1.0, 1.0)):
        await b.run(prog, [(2, cos), (3, x), (4, -sin), (5, sin), (6, y)])
        rx, ry = await b.reg(0), await b.reg(1)
        assert close32(rx, f32(-sin * y + f32(cos * x))), f"rx = {rx}"
        assert close32(ry, f32(cos * y + f32(sin * x))), f"ry = {ry}"


@cocotb.test()
async def test_fstep_frcp(dut):
    """FSTEP (strictly x > 0) and the LUT reciprocal FRCP (within 0.5%)."""
    b = await trained_borg(dut)
    for val, want in ((1.0, 1.0), (0.5, 1.0), (0.0, 0.0), (-0.5, 0.0), (-1.0, 0.0)):
        await b.run([regs.encode_rv32_fstep(rs1=0, rd=1)], [(0, val)])
        got = await b.reg(1)
        assert got == want, f"FSTEP({val}) = {got}, want {want}"
    for val, want in ((1.0, 1.0), (2.0, 0.5), (4.0, 0.25), (0.5, 2.0), (0.25, 4.0)):
        await b.run([regs.encode_rv32_frcp(rs1=0, rd=1)], [(0, val)])
        got = await b.reg(1)
        assert abs(got - want) / want < 0.005, f"FRCP({val}) = {got}, want ~{want}"


def chip_link_runner():
    proj_path = Path(__file__).resolve().parent

    sources = []
    defines = {f"SLOT_{slot.upper()}": True}
    includes = [proj_path / "../src/"]

    defines[f"PDK_{pdk.replace('-','_')}"] = True
    defines[f"SCL_{scl}"] = True
    defines[f"PAD_{pad}"] = True
    defines[f"SRAM_{sram}"] = True

    if gl:
        scl_dir = Path(pdk_root) / pdk / "libs.ref" / scl / "verilog"
        sources.append(scl_dir / f"{scl}.v")
        if (scl_dir / "primitives.v").exists():
            sources.append(scl_dir / "primitives.v")
        sources.append(Path(pdk_root) / pdk / "libs.ref" / pad / "verilog" / f"{pad}.v")
        sources.extend(sorted((proj_path / "../ip").glob("*/vh/*.v")))
        if not gl_netlist.is_file():
            raise SystemExit(f"{gl_netlist} missing")
        sources.append(gl_netlist)
        defines["FUNCTIONAL"] = True
        defines["GL"] = True
        if powered:
            defines["USE_POWER_PINS"] = True
    else:
        sources.append(Path(pdk_root) / pdk / "libs.ref" / pad / "verilog" / f"{pad}.v")
        sources.append(proj_path / "../src/chip_top.sv")
        sources.append(proj_path / "../src/chip_core.sv")
        # wafer.space's QR/shuttle/project-ID, marker and logo cells. Physical
        # only (geometry for the reticle), but chip_top instantiates them with
        # (* keep *) "necessary for tapeout", so simulation needs their stubs.
        sources.extend(sorted((proj_path / "../ip").glob("*/vh/*.v")))
        # BorgOnlyTop plus every module it pulls in (Borg, the link slave, the
        # inferred memories). Emitted by `make generate_verilog_wafer`.
        emit = "verilog_wafer_1x1" if slot == "1x1" else "verilog_wafer"
        wafer = proj_path / "../../../out/hardware/borg" / emit
        if not wafer.is_dir():
            raise SystemExit(
                f"{wafer} missing -- run `make generate_{emit}` first"
            )
        sources.extend(sorted(wafer.glob("*.sv")))

    sources.append(proj_path / "chip_link_tb.sv")

    # One build directory per netlist kind, so an RTL run and a gate-level
    # run can go at the same time without clobbering each other.
    build_dir = proj_path / ("sim_build_" + ("rtl" if not gl else "pnl" if powered else "gl"))

    runner = get_runner(sim)
    runner.build(
        build_dir=build_dir,
        sources=sources,
        includes=includes,
        defines=defines,
        hdl_toplevel=hdl_toplevel,
        always=True,
        timescale=("1ns", "1ps"),
    )
    runner.test(hdl_toplevel=hdl_toplevel, test_module="chip_link_tb",
                build_dir=build_dir)


if __name__ == "__main__":
    chip_link_runner()
