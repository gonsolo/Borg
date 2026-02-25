# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

from cocotb.clock import Clock
from tqv import TinyQV
import cocotb
import json
import numpy as np
import os
import struct


def float_to_bits(f):
    return struct.unpack("<I", struct.pack("<f", f))[0]


def bits_to_float(b):
    return struct.unpack("<f", struct.pack("<I", b & 0xFFFFFFFF))[0]


def load_test_data():
    curr_dir = os.path.dirname(os.path.abspath(__file__))
    json_path = os.path.join(curr_dir, "..", "data", "test_cases.json")
    with open(json_path, "r") as f:
        return json.load(f)


class BorgDriver:
    def __init__(self, tqv):
        self.tqv = tqv
        # Address Map matches Borg.scala
        self.ADDR_STATUS = 16
        self.ADDR_IMEM = 32
        self.ADDR_CONTROL = 60

    async def write_reg(self, reg_idx, val_float):
        # Register file at 0x00 - 0x0C
        addr = reg_idx * 4
        bits = float_to_bits(np.float32(val_float))
        await self.tqv.write_word_reg(addr, bits)

    async def write_imem(self, idx, instr_bits):
        # Instruction memory at 0x20 - 0x38
        addr = self.ADDR_IMEM + (idx * 4)
        await self.tqv.write_word_reg(addr, instr_bits)

    async def start_execution(self, reset_pc=False):
        # Control register at 0x3C (60)
        # Bit 0 = Start, Bit 1 = Reset PC
        val = 1
        if reset_pc:
            val |= 2
        await self.tqv.write_word_reg(self.ADDR_CONTROL, val)

    async def wait_for_halt(self):
        # Status register at 0x10 (16)
        # Bit 1 = Halted
        while True:
            status = await self.tqv.read_word_reg(self.ADDR_STATUS)
            if status & 2:
                break
            await cocotb.triggers.Timer(100, units="ns")

    async def read_register(self, reg_idx):
        addr = reg_idx * 4
        bits = await self.tqv.read_word_reg(addr)
        return bits_to_float(bits)

    async def reset(self):
        await self.tqv.reset()


async def run_basic_math_test(dut, driver, a, b, epsilon):
    a_32 = np.float32(a)
    b_32 = np.float32(b)

    # 1. Reset PC and stop execution
    await driver.start_execution(reset_pc=True)

    # 2. Upload Data to registers 0 and 1
    await driver.write_reg(0, a_32)
    await driver.write_reg(1, b_32)

    # 3. Setup Addition Instruction in imem(0)
    # funct7=0x00 (Add), rs2=1, rs1=0, rd=2
    instr_add = (0x00 << 25) | (1 << 20) | (0 << 15) | (2 << 7)
    await driver.write_imem(0, instr_add)

    # Halt instruction (zero) in imem(1)
    await driver.write_imem(1, 0)

    # 4. Start execution
    await driver.start_execution()

    # 5. Wait for Halted status
    await driver.wait_for_halt()

    # 6. Read Result from register 2
    add_res = await driver.read_register(2)

    # 7. Assertions
    expected_add = a_32 + b_32

    assert (
        abs(add_res - expected_add) < epsilon
    ), f"Add failed: {a_32}+{b_32}={add_res} (Exp: {expected_add})"

    dut._log.info(
        f"Checked Shader Adder: {a_32:8.2f} + {b_32:8.2f} -> Result: {add_res:8.2f}"
    )


PERIPHERAL_NUM = 39


@cocotb.test()
async def test_borg_vulkan_style_math(dut):
    dut._log.info("Starting Programmable Borg Shading Processor Test")

    test_data = load_test_data()
    epsilon = test_data["epsilon"]

    clock = Clock(dut.clk, 100, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)
    driver = BorgDriver(tqv)
    await driver.reset()

    for a, b in test_data["pairs"]:
        await run_basic_math_test(dut, driver, a, b, epsilon)

    dut._log.info("All Borg Shading Processor Tests Passed!")
