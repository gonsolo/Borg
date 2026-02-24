# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

import os
import json
import struct
import numpy as np
import cocotb
from cocotb.clock import Clock
from tqv import TinyQV


class BorgDriver:
    """
    Driver to abstract TinyQV bus transactions into Borg-specific actions.
    Mirroring the Scala BorgDriver to maintain cross-environment consistency.
    """

    def __init__(self, dut, tqv):
        self.dut = dut
        self.tqv = tqv
        self.ADDR_STATUS = 16
        self.ADDR_IMEM = 32
        self.ADDR_CONTROL = 60

    def float_to_bits(self, f):
        return struct.unpack("<I", struct.pack("<f", np.float32(f)))[0]

    def bits_to_float(self, b):
        return struct.unpack("<f", struct.pack("<I", b & 0xFFFFFFFF))[0]

    async def write_reg(self, reg_idx, val):
        """Writes a float to Register File index (0-3)"""
        addr = reg_idx * 4
        await self.tqv.write_word_reg(addr, self.float_to_bits(val))

    async def write_imem(self, idx, instr_bits):
        """Writes instruction bits to instruction memory (0-7)"""
        addr = self.ADDR_IMEM + (idx * 4)
        await self.tqv.write_word_reg(addr, instr_bits)

    async def start_execution(self, reset_pc=False):
        """Triggers execution via the control register"""
        val = 1
        if reset_pc:
            val |= 2
        await self.tqv.write_word_reg(self.ADDR_CONTROL, val)

    async def wait_for_halt(self):
        """Polls the status register for the Halted bit (bit 1)"""
        while True:
            status = await self.tqv.read_word_reg(self.ADDR_STATUS)
            if status & 2:
                break
            await cocotb.triggers.Timer(100, unit="ns")

    async def read_register(self, reg_idx):
        """Reads a float from Register File index (0-3)"""
        addr = reg_idx * 4
        bits = await self.tqv.read_word_reg(addr)
        return self.bits_to_float(bits)

    async def reset(self):
        await self.tqv.reset()


def load_test_data():
    curr_dir = os.path.dirname(os.path.abspath(__file__))
    # Adjust path to find the test vectors in the shared data directory
    json_path = os.path.join(
        curr_dir, "..", "..", "..", "borg_peripheral", "data", "test_cases.json"
    )
    if not os.path.exists(json_path):
        raise FileNotFoundError(f"Shared test vectors not found at: {json_path}")
    with open(json_path, "r") as f:
        return json.load(f)


async def run_math_test(dut, driver, a, b, epsilon):
    """
    Executes a single shader-based math test case (Addition only).
    """
    a_32, b_32 = np.float32(a), np.float32(b)

    # 1. Reset PC and stop execution
    await driver.start_execution(reset_pc=True)

    # 2. Load Operands into regs 0 and 1
    await driver.write_reg(0, a_32)
    await driver.write_reg(1, b_32)

    # 3. Setup Shader: imem(0) = ADD, imem(1) = HALT
    # funct7=0x00 (Add), rs2=1, rs1=0, rd=2
    instr_add = (0x00 << 25) | (1 << 20) | (0 << 15) | (2 << 7)
    await driver.write_imem(0, instr_add)
    await driver.write_imem(1, 0)

    # 4. Start execution
    await driver.start_execution()

    # 5. Wait for Halted status
    await driver.wait_for_halt()

    # 6. Read Result from register 2
    add_res = await driver.read_register(2)

    # 7. Assertions
    expected_sum = a_32 + b_32

    assert (
        abs(add_res - expected_sum) < epsilon
    ), f"Add failed: {a_32} + {b_32} = {add_res} (Exp: {expected_sum})"

    dut._log.info(
        f"Verified Shader: {a_32:8.2f} + {b_32:8.2f} -> Result: {add_res:8.2f}"
    )


PERIPHERAL_NUM = 39


@cocotb.test()
async def test_borg_vulkan_style_math(dut):
    dut._log.info("Starting Programmable Borg Shading Processor Integration Test")

    test_data = load_test_data()
    clock = Clock(dut.clk, 100, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)
    driver = BorgDriver(dut, tqv)
    await driver.reset()

    for a, b in test_data["pairs"]:
        await run_math_test(dut, driver, a, b, test_data["epsilon"])

    # Final sanity check on Register 0 to ensure stability
    read_bits_0 = await tqv.read_word_reg(0)
    last_val_0 = np.float32(test_data["pairs"][-1][0])
    assert read_bits_0 == driver.float_to_bits(last_val_0), "Register 0 corrupted!"

    dut._log.info("Borg Shading Processor Integration Test Passed!")
