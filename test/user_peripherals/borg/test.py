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
        self.ADDR_A = 0
        self.ADDR_B = 4
        self.ADDR_ADD = 8
        self.ADDR_MUL = 12
        self.ADDR_C = 16

    def float_to_bits(self, f):
        return struct.unpack("<I", struct.pack("<f", np.float32(f)))[0]

    def bits_to_float(self, b):
        return struct.unpack("<f", struct.pack("<I", b & 0xFFFFFFFF))[0]

    async def write_reg(self, reg_idx, val):
        """Writes a float to Register File index (mapping to 0, 4, or 16)"""
        if reg_idx == 0:
            addr = self.ADDR_A
        elif reg_idx == 1:
            addr = self.ADDR_B
        else:
            addr = self.ADDR_C
        await self.tqv.write_word_reg(addr, self.float_to_bits(val))

    async def read_float(self, addr):
        """Reads a 32-bit value from the bus and converts to float"""
        bits = await self.tqv.read_word_reg(addr)
        return self.bits_to_float(bits)

    async def reset(self):
        await self.tqv.reset()

def load_test_data():
    curr_dir = os.path.dirname(os.path.abspath(__file__))
    json_path = os.path.join(
        curr_dir, "..", "..", "..", "borg_peripheral", "data", "test_cases.json"
    )
    if not os.path.exists(json_path):
        raise FileNotFoundError(f"Shared test vectors not found at: {json_path}")
    with open(json_path, "r") as f:
        return json.load(f)

async def run_math_test(dut, driver, a, b, epsilon):
    """
    Executes a single math test case. 
    Factored out to mirror runBasicMathTest in Scala.
    """
    a_32, b_32 = np.float32(a), np.float32(b)
    
    # 1. Load Operands
    await driver.write_reg(0, a_32)
    await driver.write_reg(1, b_32)

    # 2. Read back results
    add_res = await driver.read_float(driver.ADDR_ADD)
    mul_res = await driver.read_float(driver.ADDR_MUL)

    # 3. Assertions
    expected_sum = a_32 + b_32
    expected_mul = a_32 * b_32
    
    assert abs(add_res - expected_sum) < epsilon, \
        f"Add failed: {a_32} + {b_32} = {add_res} (Exp: {expected_sum})"
    assert abs(mul_res - expected_mul) < epsilon, \
        f"Mul failed: {a_32} * {b_32} = {mul_res} (Exp: {expected_mul})"

    dut._log.info(f"Verified: {a_32} and {b_32} (Add: {add_res}, Mul: {mul_res})")

PERIPHERAL_NUM = 39

@cocotb.test()
async def test_borg_vulkan_style_math(dut):
    dut._log.info("Starting Modular Borg Test in TinyQV Integration")

    test_data = load_test_data()
    clock = Clock(dut.clk, 100, unit="ns")
    cocotb.start_soon(clock.start())
    
    tqv = TinyQV(dut, PERIPHERAL_NUM)
    driver = BorgDriver(dut, tqv)
    await driver.reset()

    for a, b in test_data["pairs"]:
        await run_math_test(dut, driver, a, b, test_data["epsilon"])

    # Final sanity check on Register A
    read_bits_a = await tqv.read_word_reg(driver.ADDR_A)
    last_val_a = np.float32(test_data["pairs"][-1][0])
    assert read_bits_a == driver.float_to_bits(last_val_a), "Operand A corrupted!"

    dut._log.info("Borg Modular Integration Test Passed!")
