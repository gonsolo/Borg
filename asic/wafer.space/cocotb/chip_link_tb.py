# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: Apache-2.0
"""Pad-level tests for the wafer.space Borg-only bridge (plan Phase 5, test 8).

Runs the link protocol against chip_top through the real gf180mcu pads, so
this covers the three things no FPGA rung can: the padring, the true w=16 lane
map, and (with GL=1) the post-P&R netlist. The lane map in particular is
unfixable after tapeout -- moving a pin is a padring change -- so it is worth
checking by construction rather than by inspection.

Kept separate from the upstream chip_top_tb.py, which still holds the
template's counter example.

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

import borg_link
from borg_link import LinkMaster

sim = os.getenv("SIM", "icarus")
gl = os.getenv("GL", False)
pdk_root = os.getenv("PDK_ROOT", Path(__file__).resolve().parent / "../gf180mcu")
pdk = os.getenv("PDK", "gf180mcuD")
scl = os.getenv("SCL", "gf180mcu_fd_sc_mcu7t5v0")
pad = os.getenv("PAD", "gf180mcu_fd_io")
sram = os.getenv("SRAM", "gf180mcu_fd_ip_sram")
# BorgOnlyTop's lane map only exists in the 1x0.5 slot (46 bidir + 4 input);
# chip_core $error()s otherwise, so default to it rather than the 1x1 slot.
slot = os.getenv("SLOT", "1x0p5")

hdl_toplevel = "chip_link_tb"


async def start_up(dut, link_fast=0, link_narrow=0, dbg_sel=0):
    """Power, straps, clock, reset -- straps must be stable before reset."""
    if gl:
        dut.VDD.value = 1
        dut.VSS.value = 0
        dut.DVDD.value = 1
        dut.DVSS.value = 0

    dut.input_drv.value = (
        (dbg_sel & 0x3) << borg_link.DBG_SEL_LO
        | (link_narrow & 1) << borg_link.LINK_NARROW
        | (link_fast & 1) << borg_link.LINK_FAST
    )
    dut.drv.value = 0
    dut.drv_oe.value = 0

    cocotb.start_soon(Clock(dut.clk, 40, "ns").start())  # 25 MHz

    dut.rst_n.value = 0
    await Timer(1000, "ns")
    dut.rst_n.value = 1
    await ClockCycles(dut.clk, 10)


@cocotb.test()
async def test_link_trains(dut):
    """The slave must reach link_up from the master's training pattern.

    Passing this already exercises a lot of the lane map: the training word
    only decodes if dn_d[15:0], dn_v and dn_p are all mapped correctly (parity
    is checked every beat), and link_up must come back on the right pad.
    """
    log = logging.getLogger("link")
    await start_up(dut)
    m = LinkMaster(dut, log)

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
    await start_up(dut)
    m = LinkMaster(dut, log)
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
    await start_up(dut)
    m = LinkMaster(dut, log)
    assert await m.train(), "link_up never asserted"
    await m.idle(4)

    # r30/r31 are the pixel-coordinate pseudo-registers (BorgLane's
    # resolveCoordReg substitutes coordX/coordY on every read port, MMIO
    # included), so they are not storage and cannot round-trip. Everything
    # below them is a real GPR.
    bad = []
    for reg in (0, 1, 2, 7, 8, 15, 16, 23, 24, 29):
        val = 0x1000 | reg
        addr = reg * 4                      # gpr[] @ 0x000, stride 4
        await m.write32(addr, val)
        await m.idle(4)
        got = await m.read32(addr, timeout_beats=120)
        if got is None:
            bad.append((reg, "no response"))
        elif (got & 0xFFFF) != val:
            bad.append((reg, f"got 0x{got & 0xFFFF:04x} want 0x{val:04x}"))
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
    await start_up(dut, link_narrow=1)
    m = LinkMaster(dut, log, narrow=True)

    assert await m.train(), (
        "link_up never asserted with link_narrow strapped -- the runtime "
        "w=16->w=8 mux is not engaging (LinkParams.narrowCapable)."
    )
    await m.idle(4)

    for reg, val in ((0, 0x3C00), (7, 0x0FF0), (29, 0x5A5A)):
        addr = reg * 4
        await m.write32(addr, val)
        await m.idle(4)
        got = await m.read32(addr)
        assert got is not None, f"no response to narrow read of gpr[{reg}]"
        assert (got & 0xFFFF) == val, (
            f"narrow gpr[{reg}]: wrote 0x{val:04x}, read back 0x{got & 0xFFFF:04x}"
        )
    assert m.parity_errors == 0, f"{m.parity_errors} parity errors in narrow mode"


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
        sources.append(Path(pdk_root) / pdk / "libs.ref" / scl / "verilog" / f"{scl}.v")
        sources.append(Path(pdk_root) / pdk / "libs.ref" / pad / "verilog" / f"{pad}.v")
        sources.append(proj_path / "../final/pnl/chip_top.pnl.v")
        defines["USE_POWER_PINS"] = True
        defines["GL"] = True
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
        wafer = proj_path / "../../../out/hardware/borg/verilog_wafer"
        if not wafer.is_dir():
            raise SystemExit(
                f"{wafer} missing -- run `make generate_verilog_wafer` first"
            )
        sources.extend(sorted(wafer.glob("*.sv")))

    sources.append(proj_path / "chip_link_tb.sv")

    runner = get_runner(sim)
    runner.build(
        sources=sources,
        includes=includes,
        defines=defines,
        hdl_toplevel=hdl_toplevel,
        always=True,
        timescale=("1ns", "1ps"),
    )
    runner.test(hdl_toplevel=hdl_toplevel, test_module="chip_link_tb")


if __name__ == "__main__":
    chip_link_runner()
