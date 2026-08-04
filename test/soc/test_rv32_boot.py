# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: MIT

"""Boot the real RV32 firmware (software/borg/kernel.bin, compiled XLEN=32)
through tt_um_gonsolo_borg's actual QSPI flash-read protocol -- verifies the
RV32 toolchain/ABI/boot sequence actually works, not just that it compiles
(see software/borg/Makefile's XLEN=32 path, commit 8f403c2).

Unlike a Verilog flash-chip model (tried first: test/soc/sim_qspi.v +
tb_qspi.v -- found and fixed one real polarity bug there, but hit a second,
deeper nibble-alignment bug and abandoned it), this drives the SAME
low-level per-nibble handshake test_util.py's start_read()/send_instr()
already use (proven correct against the real QspiController by every other
passing cocotb test in this suite) -- just generalized into a persistent
loop that serves real bytes from a preloaded kernel.bin buffer at whatever
address the CPU actually requests, instead of one hand-crafted instruction
per call.

Verifies start.s's 3 boot beacons ('a' at _start, 'b' after sp setup, 'c'
after __runtime_init returns) arrive over the debug UART in order.
"""

import os

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles, Timer
from cocotb.utils import get_sim_time

from test_util import reset

CLOCK_MHZ = int(os.environ.get("CLOCK_MHZ", "4"))
CLOCK_PERIOD_PS = int(1000000.0 / CLOCK_MHZ)
if CLOCK_PERIOD_PS % 2 != 0:
    CLOCK_PERIOD_PS += 1

# Same interleaving test_util.py's send_instr() uses to place a 32-bit
# little-endian word's 8 nibbles onto the bus: upper-then-lower nibble of
# byte 0, then byte 1, byte 2, byte 3 -- matches how a real QSPI flash
# chip streams bytes MSB-nibble-first.
NIBBLE_SHIFT_ORDER = [4, 0, 12, 8, 20, 16, 28, 24]


def word_at(memory: bytes, addr: int) -> int:
    """Little-endian 32-bit word from `memory` at byte address `addr`
    (out-of-range bytes read as 0, matching real flash beyond the image)."""
    b = bytes(memory[addr + i] if addr + i < len(memory) else 0 for i in range(4))
    return int.from_bytes(b, "little")


RAM_BANK_BYTES = 0x800000  # 8 MB per bank (test_util.py's addr thresholds put
                            # the ram_a/ram_b split at 0x1000000/0x1800000,
                            # but the wire address is chip-relative -- see
                            # flash_server's RAM branch)


def _nibble_or_zero(sig) -> int:
    """Read a 4-bit signal, treating X/Z as 0 instead of raising. Real
    firmware legitimately drives X onto the bus sometimes (storing an
    uninitialized register -- e.g. __runtime_init's prologue saves s0
    before it's ever been written); that's not a harness bug."""
    try:
        return int(sig.value) & 0xF
    except ValueError:
        return 0


async def _consume_cmd_addr(dut):
    """Consume the 2-nibble command byte + 6-nibble (24-bit) address phase
    common to every transaction (flash or RAM, read or write). Returns
    (cmd_byte, addr) -- capturing both instead of asserting against known
    values, unlike test_util.py's start_read/start_write which are only
    ever called with an address already known in advance."""
    cmd = 0
    for _ in range(2):
        for _ in range(20):
            await ClockCycles(dut.clk, 1, False)
            if dut.qspi_clk_out.value == 1:
                break
        cmd = (cmd << 4) | _nibble_or_zero(dut.qspi_data_out)
        for _ in range(20):
            await ClockCycles(dut.clk, 1, False)
            if dut.qspi_clk_out.value == 0:
                break
    addr = 0
    for _ in range(6):
        for _ in range(20):
            await ClockCycles(dut.clk, 1, False)
            if dut.qspi_clk_out.value == 1:
                break
        addr = (addr << 4) | _nibble_or_zero(dut.qspi_data_out)
        for _ in range(20):
            await ClockCycles(dut.clk, 1, False)
            if dut.qspi_clk_out.value == 0:
                break
    return cmd, addr


async def _drive_nibbles_until_deselect(dut, select_sig, values):
    """Drive each nibble in `values` onto qspi_data_in, handshaking on the
    controller's own per-nibble ready pulse (matches send_instr's proven
    pattern) -- used for read-direction data phases (flash and RAM reads)."""
    for v in values:
        dut.qspi_data_in.value = v & 0xF
        await ClockCycles(dut.clk, 1, False)
        for _ in range(80):
            if select_sig.value == 1:
                return
            if (dut.qspi_clk_out.value == 1 and dut.qspi_data_oe.value == 0
                    and dut.qspi_data_out.value == 0xF):
                break
            await ClockCycles(dut.clk, 1, False)
        if select_sig.value == 1:
            return
        await ClockCycles(dut.clk, 1, False)


async def _sample_nibbles_until_deselect(dut, select_sig, count):
    """Sample `count` nibbles being driven BY the CPU onto qspi_data_out
    (write-direction data phase) -- mirrors expect_store's sampling loop:
    each nibble is valid on the qspi_clk_out posedge, while the CPU has the
    bus output-enabled (data_oe==0xF). Polls for that condition (like
    _drive_nibbles_until_deselect polls for the read-direction handshake)
    instead of assuming a fixed cycle offset.

    Firmware legitimately stores uninitialized registers sometimes (e.g.
    __runtime_init's prologue saves s0 before it's ever been written) --
    that shows up here as X on qspi_data_out. Real hardware would store
    whatever garbage was in the flop; for this harness, X reads as 0 rather
    than crashing, since the value is never meaningfully read back."""
    nibbles = []
    for _ in range(count):
        if select_sig.value == 1:
            break
        for _ in range(80):
            await ClockCycles(dut.clk, 1, False)
            if select_sig.value == 1:
                break
            if dut.qspi_clk_out.value == 1 and dut.qspi_data_oe.value == 0xF:
                break
        if select_sig.value == 1:
            break
        nibbles.append(_nibble_or_zero(dut.qspi_data_out))
        await ClockCycles(dut.clk, 1, False)
    return nibbles


def _nibbles_to_word(nibbles):
    val = 0
    for j, n in enumerate(nibbles):
        val |= n << NIBBLE_SHIFT_ORDER[j % 8]
    return val


async def flash_server(dut, memory: bytes):
    """Persistent QSPI memory server for all three chips (flash, ram_a,
    ram_b) sharing the bus. Flash: read-only, served from the preloaded
    `memory` buffer, one CMD+ADDR+dummy+full-32-bit-word-stream transaction
    per instruction fetch (matches observed instruction-fetch behaviour).
    RAM: read/write, backed by a Python bytearray per bank -- the
    MemoryController decomposes every CPU data access into one or two
    16-bit halfword transactions (addr, then addr+2), each a fresh
    CMD+ADDR+data burst with no dummy cycles (see expect_load/expect_store
    in test_util.py) -- so each RAM select is a single halfword, and the
    CPU re-selects for the second half of a 32-bit access on its own.
    Runs forever; the caller just awaits boot progress.
    """
    ram_a = bytearray(RAM_BANK_BYTES)
    ram_b = bytearray(RAM_BANK_BYTES)

    while True:
        # Wait for any chip select to go active (low), and note which.
        while (dut.qspi_flash_select.value != 0 and dut.qspi_ram_a_select.value != 0
               and dut.qspi_ram_b_select.value != 0):
            await ClockCycles(dut.clk, 1, False)

        if dut.qspi_flash_select.value == 0:
            select_sig = dut.qspi_flash_select
            _cmd, addr = await _consume_cmd_addr(dut)
            # Flash dummy cycles (2, read-only -- see QspiCtrl DUMMY1+DUMMY2
            # for the non-flash-select path, and start_read's own dummy loop).
            for _ in range(2):
                await ClockCycles(dut.clk, 1, False)
                await ClockCycles(dut.clk, 1, False)
            word_addr = addr
            while select_sig.value == 0:
                data = word_at(memory, word_addr)
                word_addr += 4
                await _drive_nibbles_until_deselect(
                    dut, select_sig, [(data >> NIBBLE_SHIFT_ORDER[i]) & 0xF for i in range(8)]
                )
            continue

        if dut.qspi_ram_a_select.value == 0:
            select_sig = dut.qspi_ram_a_select
            bank = ram_a
        else:
            select_sig = dut.qspi_ram_b_select
            bank = ram_b

        cmd, addr = await _consume_cmd_addr(dut)
        # QspiController transmits the in-chip-relative offset (0-based,
        # same as flash) -- the select line, not an address bit, tells the
        # chip which bank it is. No RAM_A_BASE/RAM_B_BASE subtraction.
        off = addr & (RAM_BANK_BYTES - 1)
        is_write = cmd == 0x02
        if is_write:
            nibbles = await _sample_nibbles_until_deselect(dut, select_sig, 4)
            val = _nibbles_to_word(nibbles)
            for i in range((len(nibbles) + 1) // 2):
                if off + i < len(bank):
                    bank[off + i] = (val >> (i * 8)) & 0xFF
        else:
            half = bank[off] | (bank[off + 1] << 8) if off + 1 < len(bank) else 0
            await _drive_nibbles_until_deselect(
                dut, select_sig, [(half >> NIBBLE_SHIFT_ORDER[i]) & 0xF for i in range(4)]
            )


async def read_uart_byte(dut, uart_signal, bit_time_ps, timeout_ns):
    """Decode one byte from an idle-high UART TX line (LSB first, 1 start +
    8 data + 1 stop bit), matching test.py's test_start decode convention."""
    start_sim_time = get_sim_time("ns")
    while uart_signal.value == 1:
        await Timer(1000, "ns")
        if get_sim_time("ns") - start_sim_time > timeout_ns:
            assert False, f"Timeout ({timeout_ns} ns) waiting for UART start bit"

    await Timer(bit_time_ps * 3 // 2, "ps")
    val = 0
    for i in range(8):
        val |= (int(uart_signal.value) & 1) << i
        await Timer(bit_time_ps, "ps")
    assert uart_signal.value == 1, "UART stop bit not high -- framing error"
    return val


@cocotb.test()
async def test_rv32_boot(dut):
    dut._log.info("Start rv32boot: booting real RV32 kernel.bin via Python flash server")

    clock = Clock(dut.clk, CLOCK_PERIOD_PS, unit="ps")
    cocotb.start_soon(clock.start())

    await reset(dut)

    with open(os.path.join(os.path.dirname(__file__), "..", "..", "software", "borg", "kernel.bin"), "rb") as f:
        firmware = f.read()
    dut._log.info(f"Loaded kernel.bin: {len(firmware)} bytes")

    cocotb.start_soon(flash_server(dut, firmware))

    baud_divider = (CLOCK_MHZ * 1000000) // 115200
    bit_time_ps = (baud_divider + 1) * CLOCK_PERIOD_PS

    # No instruction cache on the ASIC config; every fetch pays a full QSPI
    # transaction. Generous timeout: 200 ms of sim time at 4 MHz = 800,000
    # cycles.
    timeout_ns = 200_000_000

    beacons = [("a", "reached _start"), ("b", "after sp setup"), ("c", "after __runtime_init")]
    for ch, desc in beacons:
        expected = ord(ch)
        got = await read_uart_byte(dut, dut.debug_uart_tx, bit_time_ps, timeout_ns)
        dut._log.info(
            f"debug UART beacon: got 0x{got:02x} ({chr(got) if 32 <= got < 127 else '?'}), "
            f"expected {ch!r} ({desc})"
        )
        assert got == expected, f"expected beacon {ch!r} ({desc}), got 0x{got:02x}"

    dut._log.info("All 3 boot beacons received in order -- RV32 firmware boots and reaches __runtime_init")
