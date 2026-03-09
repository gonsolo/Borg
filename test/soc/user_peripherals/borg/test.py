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

    def __init__(self, dut, tqv, is_fp16=True):
        self.dut = dut
        self.tqv = tqv
        self.is_fp16 = is_fp16
        self.ADDR_STATUS = 16
        self.ADDR_IMEM = 32
        self.ADDR_CONTROL = 60

    def is_close(self, actual, expected):
        # Use a relative epsilon based on the config's precision
        rel_eps = 1e-3 if self.is_fp16 else 1e-6
        tolerance = max(rel_eps * abs(expected), rel_eps)
        return abs(actual - expected) < tolerance

    def float_to_bits(self, f):
        if self.is_fp16:
            # IEEE 754 Half-precision (16-bit)
            return np.array([f], dtype=np.float16).view(np.uint16)[0]
        else:
            # IEEE 754 Single-precision (32-bit)
            return struct.unpack("<I", struct.pack("<f", np.float32(f)))[0]

    def bits_to_float(self, b):
        if self.is_fp16:
            # Mask to 16 bits as hardware zero-extends in Peripherals.scala
            b16 = b & 0xFFFF
            return np.array([b16], dtype=np.uint16).view(np.float16)[0]
        else:
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
        curr_dir, "..", "..", "..", "..", "data", "test_cases.json"
    )
    if not os.path.exists(json_path):
        raise FileNotFoundError(f"Shared test vectors not found at: {json_path}")
    with open(json_path, "r") as f:
        return json.load(f)


async def run_math_test(dut, driver, a, b):
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
    # Use appropriate precision for expected value calculation
    if driver.is_fp16:
        expected_sum = np.float16(np.float16(a) + np.float16(b))
    else:
        expected_sum = np.float32(a_32 + b_32)

    assert driver.is_close(
        add_res, expected_sum
    ), f"Add failed: {a_32} + {b_32} = {add_res} (Exp: {expected_sum})"

    dut._log.info(
        f"Verified Shader: {a_32:8.2f} + {b_32:8.2f} -> Result: {add_res:8.2f}"
    )


PERIPHERAL_NUM = 3


async def run_mul_test(dut, driver, a, b):
    """
    Executes a single shader-based MUL test case.
    """
    a_32, b_32 = np.float32(a), np.float32(b)

    await driver.start_execution(reset_pc=True)

    await driver.write_reg(0, a_32)
    await driver.write_reg(1, b_32)

    # FP16: bits[15:13]=001 (MUL), rs2=1, rs1=0, rd=2
    rs1, rs2, rd = 0, 1, 2
    if driver.is_fp16:
        instr_mul = (1 << 13) | (rs2 << 8) | (rs1 << 5) | (rd << 2)
    else:
        instr_mul = (0x4 << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7)

    await driver.write_imem(0, instr_mul)
    await driver.write_imem(1, 0)

    await driver.start_execution()
    await driver.wait_for_halt()

    mul_res = await driver.read_register(2)

    if driver.is_fp16:
        expected = float(np.float16(np.float16(a) * np.float16(b)))
    else:
        expected = float(np.float32(a_32 * b_32))

    assert driver.is_close(
        mul_res, expected
    ), f"Mul failed: {a_32} * {b_32} = {mul_res} (Exp: {expected})"

    dut._log.info(
        f"Verified MUL: {a_32:8.2f} * {b_32:8.2f} -> Result: {mul_res:8.2f}"
    )


async def run_fma_test(dut, driver, a, b, c):
    """
    Executes a single shader-based FMA test case (a * b + c).
    """
    a_32, b_32, c_32 = np.float32(a), np.float32(b), np.float32(c)

    await driver.start_execution(reset_pc=True)

    # a→reg0, b→reg1, c→reg3
    await driver.write_reg(0, a_32)
    await driver.write_reg(1, b_32)
    await driver.write_reg(3, c_32)

    # rd=2, rs1=0, rs2=1, rs3=3
    rs1, rs2, rs3, rd = 0, 1, 3, 2
    if driver.is_fp16:
        # bits[15:13]=010 (FMA), bits[12:11]=rs3
        instr_fma = (2 << 13) | (rs3 << 11) | (rs2 << 8) | (rs1 << 5) | (rd << 2)
    else:
        # bit 2 = FMA flag, rs3 in [31:27]
        instr_fma = (rs3 << 27) | (rs2 << 20) | (rs1 << 15) | (rd << 7) | (1 << 2)

    await driver.write_imem(0, instr_fma)
    await driver.write_imem(1, 0)

    await driver.start_execution()
    await driver.wait_for_halt()

    fma_res = await driver.read_register(2)

    if driver.is_fp16:
        expected = float(np.float16(np.float16(a) * np.float16(b) + np.float16(c)))
    else:
        expected = float(np.float32(a_32 * b_32 + c_32))

    assert driver.is_close(
        fma_res, expected
    ), f"FMA failed: {a_32} * {b_32} + {c_32} = {fma_res} (Exp: {expected})"

    dut._log.info(
        f"Verified FMA: {a_32:8.2f} * {b_32:8.2f} + {c_32:8.2f} -> Result: {fma_res:8.2f}"
    )


@cocotb.test()
async def test_borg_shader_math_batch(dut):
    dut._log.info("Starting Borg Shader Math Batch Integration Test")

    test_data = load_test_data()
    clock = Clock(dut.clk, 100, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)
    driver = BorgDriver(dut, tqv)
    await driver.reset()

    # --- ADD tests ---
    dut._log.info("--- ADD ---")
    for a, b in test_data["pairs"]:
        await run_math_test(dut, driver, a, b)

    # --- MUL tests ---
    dut._log.info("--- MUL ---")
    for a, b in test_data["pairs"]:
        if driver.is_fp16 and abs(a * b) > 65504:
            continue
        await run_mul_test(dut, driver, a, b)

    # --- FMA tests ---
    dut._log.info("--- FMA ---")
    for a, b in test_data["pairs"]:
        for c in [1.0, -0.5]:
            if driver.is_fp16 and abs(a * b + c) > 65504:
                continue
            await run_fma_test(dut, driver, a, b, c)

    dut._log.info("Borg ADD/MUL/FMA Integration Test Passed!")
