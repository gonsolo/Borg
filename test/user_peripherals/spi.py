# SPDX-FileCopyrightText: © 2024 Tiny Tapeout
# SPDX-License-Identifier: Apache-2.0

import random

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles

from tqv import TinyQV

PERIPHERAL_NUM = 30

async def transfer_byte(dut, spi_byte, spi_byte_in, divider=4, start_txn=True, end_txn=True, dc=0):
    dut._log.debug(f"Transfer byte: Start {start_txn}, End: {end_txn}")
    if start_txn:
        assert dut.spi_cs.value == 1
        for i in range(20):
            await ClockCycles(dut.clk, 1)
            if dut.spi_cs.value == 0:
                break
        assert dut.spi_cs.value == 0
    else:
        assert dut.spi_cs.value == 0
        assert dut.spi_sck.value == 0
        dut.spi_miso.value = (1 if (spi_byte_in & 0x80) else 0)
        for i in range(20 + divider*2):
            await ClockCycles(dut.clk, 1)
            if dut.spi_sck.value == 1:
                break
        assert dut.spi_sck.value == 1

    assert dut.spi_dc.value == dc

    for i in range(8):
        if i != 0 or start_txn:
            assert dut.spi_cs.value == 0
            assert dut.spi_sck.value == 0
            assert dut.spi_mosi.value == (1 if (spi_byte & 0x80) else 0)
            assert dut.spi_dc.value == dc
            dut.spi_miso.value = (1 if (spi_byte_in & 0x80) else 0)
            await ClockCycles(dut.clk, divider)
        assert dut.spi_cs.value == 0
        assert dut.spi_sck.value == 1
        assert dut.spi_mosi.value == (1 if (spi_byte & 0x80) else 0)
        assert dut.spi_dc.value == dc
        await ClockCycles(dut.clk, divider)
        spi_byte <<= 1
        spi_byte_in <<= 1

    assert dut.spi_sck.value == 0
    assert dut.spi_cs.value == 0

    if end_txn:
        await ClockCycles(dut.clk, divider)
        assert dut.spi_cs.value == 1

@cocotb.test()
async def test_simple(dut):
    dut._log.info("Start")

    # Set the clock period to 64 MHz
    clock = Clock(dut.clk, 15.624, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)

    # Reset
    await tqv.reset()

    dut._log.info("Test byte transfer")

    # SPI starts idle, end_txn enabled
    assert await tqv.read_reg(0) == 4

    # Transfer a byte in and out
    tx_byte = random.randint(0, 255)
    rx_byte = random.randint(0, 255)
    await tqv.write_reg(1, tx_byte, False)
    await transfer_byte(dut, tx_byte, rx_byte)
    assert await tqv.read_reg(2) == rx_byte

    dut._log.info("Test maximum speed")

    # Change divider to minimum, read latency 1.
    await tqv.write_reg(4, 0x80)

    # Still works
    tx_byte = random.randint(0, 255)
    rx_byte = random.randint(0, 255)
    await tqv.write_reg(1, tx_byte, False)
    await transfer_byte(dut, tx_byte, rx_byte, 1)
    assert await tqv.read_reg(2) == rx_byte

    dut._log.info("Test dividers")

    # Test a range of dividers
    for i in range(1, 10):
        await tqv.write_reg(4, i)

        # Still works
        tx_byte = random.randint(0, 255)
        rx_byte = random.randint(0, 255)
        await tqv.write_reg(1, tx_byte, False)
        await transfer_byte(dut, tx_byte, rx_byte, i+1)
        assert await tqv.read_reg(2) == rx_byte

    for i in range(15, 128, 8):
        await tqv.write_reg(4, i)

        # Still works
        tx_byte = random.randint(0, 255)
        rx_byte = random.randint(0, 255)
        await tqv.write_reg(1, tx_byte, False)
        await transfer_byte(dut, tx_byte, rx_byte, i+1)
        assert await tqv.read_reg(2) == rx_byte

@cocotb.test()
async def test_multi_byte(dut):
    dut._log.info("Start")

    # Set the clock period to 64 MHz
    clock = Clock(dut.clk, 15.624, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)

    # Reset
    await tqv.reset()

    dut._log.info("Test slow 16 byte transmit")

    divider = 24
    await tqv.write_reg(4, divider-1, False)

    await tqv.write_reg(0, 0, False)
    transfer_tasks = []
    for i in range(16):
        while (await tqv.read_reg(0) & 2) != 0:
            pass

        if i == 15:
            await tqv.write_reg(0, 4, False)

        tx_data = random.randint(0, 255)
        await tqv.write_reg(1, tx_data, False)

        async def start_next_transfer(tx_data, i):
            if i != 0:
                await transfer_tasks[i-1]
            transfer_tasks.append(cocotb.start_soon(transfer_byte(dut, tx_data, i, divider=divider, start_txn=(i == 0), end_txn=(i == 15))))
        cocotb.start_soon(start_next_transfer(tx_data, i))

    assert await tqv.read_reg(0) == 7
    while len(transfer_tasks) < 16:
        await ClockCycles(dut.clk, divider)
    await transfer_tasks[15]

    assert await tqv.read_reg(0) == 4
    assert await tqv.read_reg(1) == tx_data
    assert await tqv.read_reg(2) == 15

    dut._log.info("Test fast 16 byte transmit")

    divider = 2
    await tqv.write_reg(4, divider-1, False)

    await tqv.write_reg(0, 0, False)
    transfer_tasks = []
    for i in range(16):
        # No need to check pending as the byte will always be transferred before the subsequent write

        if i == 15:
            await tqv.write_reg(0, 4, False)

        tx_data = random.randint(0, 255)
        await tqv.write_reg(1, tx_data, False)

        async def start_next_transfer(tx_data, i):
            if i != 0:
                await transfer_tasks[i-1]
            transfer_tasks.append(cocotb.start_soon(transfer_byte(dut, tx_data, i, divider=divider, start_txn=(i == 0), end_txn=(i == 15))))
        cocotb.start_soon(start_next_transfer(tx_data, i))

    assert await tqv.read_reg(0) == 5
    await transfer_tasks[15]

    assert await tqv.read_reg(0) == 4
    assert await tqv.read_reg(1) == tx_data
    assert await tqv.read_reg(2) == 15

    dut._log.info("Test 16 byte read")

    divider = 24
    await tqv.write_reg(4, divider-1, False)

    await tqv.write_reg(0, 0, False)
    for i in range(16):
        if i == 15:
            await tqv.write_reg(0, 4, False)

        tx_data = random.randint(0, 255)
        await tqv.write_reg(1, tx_data, False)
        rx_data = random.randint(0, 255)

        transfer_task = cocotb.start_soon(transfer_byte(dut, tx_data, rx_data, divider=divider, start_txn=(i == 0), end_txn=(i == 15)))
        while (await tqv.read_reg(0) & 1) != 0:
            pass
        assert await tqv.read_reg(2) == rx_data
        await transfer_task

@cocotb.test()
async def test_dc(dut):
    dut._log.info("Start")

    # Set the clock period to 64 MHz
    clock = Clock(dut.clk, 15.624, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)

    # Reset
    await tqv.reset()

    dut._log.info("Test command then data")

    divider = 4

    await tqv.write_reg(0, 8, False)
    transfer_tasks = []
    for i in range(16):
        while (await tqv.read_reg(0) & 2) != 0:
            pass

        if i == 1:
            await tqv.write_reg(0, 0, False)
        
        if i == 15:
            await tqv.write_reg(0, 4, False)

        tx_data = random.randint(0, 255)
        await tqv.write_reg(1, tx_data, False)

        async def start_next_transfer(tx_data, i):
            if i != 0:
                await transfer_tasks[i-1]
            transfer_tasks.append(cocotb.start_soon(transfer_byte(dut, tx_data, i, divider=divider, start_txn=(i == 0), end_txn=(i == 15), dc=(1 if i == 0 else 0))))
        cocotb.start_soon(start_next_transfer(tx_data, i))

    assert await tqv.read_reg(0) == 5
    while len(transfer_tasks) < 16:
        await ClockCycles(dut.clk, divider)
    await transfer_tasks[15]

    assert await tqv.read_reg(0) == 4
    assert await tqv.read_reg(1) == tx_data
    assert await tqv.read_reg(2) == 15
