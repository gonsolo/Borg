# SPDX-FileCopyrightText: © 2024 Tiny Tapeout
# SPDX-License-Identifier: Apache-2.0

import random

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles, Timer

from tqv import TinyQV

PERIPHERAL_NUM = 3

REG_ENABLE = 0x00
REG_CONTROLLER1 = 0x04
REG_CONTROLLER2 = 0x06
REG_CONTROLLER_STATE_BASE = 0x20

async def send_game_data(dut, game_word):
    val = game_word
    for _ in range(24):
        dut.game_data.value = (1 if val & 0x800000 else 0)
        await Timer(5, "us")
        dut.game_clk.value = 1
        await Timer(5, "us")
        dut.game_clk.value = 0
        val <<= 1
    
    await Timer(5, "us")
    dut.game_latch.value = 1
    await Timer(5, "us")
    dut.game_latch.value = 0


@cocotb.test()
async def test_project(dut):
    dut._log.info("Start")
    
    clock = Clock(dut.clk, 15.624, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)

    # Reset
    await tqv.reset()
    
    dut._log.info("Test register access")

    # Initial state
    value = await tqv.read_byte_reg(REG_ENABLE)
    assert value == 0
    value = await tqv.read_word_reg(REG_ENABLE)
    assert value == 0
    value = await tqv.read_hword_reg(REG_CONTROLLER1)
    assert value == 0xFFF
    value = await tqv.read_hword_reg(REG_CONTROLLER2)
    assert value == 0xFFF
    value = await tqv.read_word_reg(REG_CONTROLLER1)
    assert value == 0xFFF0FFF

    await tqv.write_byte_reg(REG_ENABLE, 1)
    value = await tqv.read_byte_reg(REG_ENABLE)
    assert value == 1

    for i in range(10):
        dut._log.info(f"Test random data read {i}")

        game_word = random.randint(0, 0xffffff)
        await send_game_data(dut, game_word)

        value = await tqv.read_hword_reg(REG_CONTROLLER1)
        assert value == game_word & 0xFFF
        value = await tqv.read_hword_reg(REG_CONTROLLER2)
        assert value == game_word >> 12
        value = await tqv.read_word_reg(REG_CONTROLLER1)
        assert value == (game_word & 0xFFF) | ((game_word << 4) & 0xFFF0000)

        for j in range(24):
            value = await tqv.read_byte_reg(REG_CONTROLLER_STATE_BASE + j)
            assert value == ((game_word >> j) & 1)

        value = await tqv.read_hword_reg(2)
        if (game_word & 0xfff) != 0xfff and (game_word & 0xfff000) != 0xfff000:
            assert value == 0x101

        value = await tqv.read_byte_reg(2)
        if (game_word & 0xfff) != 0xfff:
            assert value == 1

        value = await tqv.read_byte_reg(3)
        if (game_word & 0xfff000) != 0xfff000:
            assert value == 1
