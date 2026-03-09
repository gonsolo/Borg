# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

import os
import json
import struct
import numpy as np
import cocotb
from cocotb.clock import Clock
from tqv import TinyQV

FP16_MAX = 65504
PERIPHERAL_NUM = 3


class BorgDriver:
    """
    Driver to abstract TinyQV bus transactions into Borg-specific actions.
    """

    def __init__(self, dut, tqv, is_fp16=True):
        self.dut = dut
        self.tqv = tqv
        self.is_fp16 = is_fp16
        self.ADDR_STATUS = 16
        self.ADDR_IMEM = 32
        self.ADDR_CONTROL = 60

    def is_close(self, actual, expected):
        rel_eps = 1e-3 if self.is_fp16 else 1e-6
        tolerance = max(rel_eps * abs(expected), rel_eps)
        return abs(actual - expected) < tolerance

    def float_to_bits(self, f):
        if self.is_fp16:
            return np.array([f], dtype=np.float16).view(np.uint16)[0]
        else:
            return struct.unpack("<I", struct.pack("<f", np.float32(f)))[0]

    def bits_to_float(self, b):
        if self.is_fp16:
            b16 = b & 0xFFFF
            return np.array([b16], dtype=np.uint16).view(np.float16)[0]
        else:
            return struct.unpack("<f", struct.pack("<I", b & 0xFFFFFFFF))[0]

    def encode_add(self, rs1=0, rs2=1, rd=2):
        if self.is_fp16:
            return (rs2 << 8) | (rs1 << 5) | (rd << 2)
        return (0x00 << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7)

    def encode_mul(self, rs1=0, rs2=1, rd=2):
        if self.is_fp16:
            return (1 << 13) | (rs2 << 8) | (rs1 << 5) | (rd << 2)
        return (0x4 << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7)

    def encode_fma(self, rs1=0, rs2=1, rs3=3, rd=2):
        if self.is_fp16:
            return (2 << 13) | (rs3 << 11) | (rs2 << 8) | (rs1 << 5) | (rd << 2)
        return (rs3 << 27) | (rs2 << 20) | (rs1 << 15) | (rd << 7) | (1 << 2)

    async def write_reg(self, reg_idx, val):
        await self.tqv.write_word_reg(reg_idx * 4, self.float_to_bits(val))

    async def write_imem(self, idx, instr_bits):
        await self.tqv.write_word_reg(self.ADDR_IMEM + (idx * 4), instr_bits)

    async def start_execution(self, reset_pc=False):
        val = 1 | (2 if reset_pc else 0)
        await self.tqv.write_word_reg(self.ADDR_CONTROL, val)

    async def wait_for_halt(self):
        while True:
            status = await self.tqv.read_word_reg(self.ADDR_STATUS)
            if status & 2:
                break
            await cocotb.triggers.Timer(100, unit="ns")

    async def read_register(self, reg_idx):
        bits = await self.tqv.read_word_reg(reg_idx * 4)
        return self.bits_to_float(bits)

    async def reset(self):
        await self.tqv.reset()

    async def run_program(self, operands, instr, rd_idx=2):
        """Load operands, run a single instruction, return result."""
        await self.start_execution(reset_pc=True)
        for reg_idx, val in operands:
            await self.write_reg(reg_idx, np.float32(val))
        await self.write_imem(0, instr)
        await self.write_imem(1, 0)  # halt
        await self.start_execution()
        await self.wait_for_halt()
        return await self.read_register(rd_idx)


def load_test_data():
    curr_dir = os.path.dirname(os.path.abspath(__file__))
    json_path = os.path.join(curr_dir, "..", "..", "..", "..", "data", "test_cases.json")
    if not os.path.exists(json_path):
        raise FileNotFoundError(f"Shared test vectors not found at: {json_path}")
    with open(json_path, "r") as f:
        return json.load(f)


def compute_expected(driver, op, a, b, c=0.0):
    """Compute expected result at hardware precision."""
    if driver.is_fp16:
        a16, b16, c16 = np.float16(a), np.float16(b), np.float16(c)
        if op == "add":
            return float(np.float16(a16 + b16))
        elif op == "mul":
            return float(np.float16(a16 * b16))
        else:
            return float(np.float16(a16 * b16 + c16))
    else:
        a32, b32, c32 = np.float32(a), np.float32(b), np.float32(c)
        if op == "add":
            return float(np.float32(a32 + b32))
        elif op == "mul":
            return float(np.float32(a32 * b32))
        else:
            return float(np.float32(a32 * b32 + c32))


async def run_op_test(dut, driver, op, a, b, c=0.0):
    """Unified test runner for ADD, MUL, FMA."""
    if op == "add":
        operands = [(0, a), (1, b)]
        instr = driver.encode_add()
        label = f"{a:8.2f} + {b:8.2f}"
    elif op == "mul":
        operands = [(0, a), (1, b)]
        instr = driver.encode_mul()
        label = f"{a:8.2f} * {b:8.2f}"
    else:
        operands = [(0, a), (1, b), (3, c)]
        instr = driver.encode_fma()
        label = f"{a:8.2f} * {b:8.2f} + {c:8.2f}"

    result = await driver.run_program(operands, instr)
    expected = compute_expected(driver, op, a, b, c)

    assert driver.is_close(
        result, expected
    ), f"{op.upper()} failed: {label} = {result} (Exp: {expected})"

    dut._log.info(f"Verified {op.upper()}: {label} -> Result: {float(result):8.2f}")


def overflows_fp16(value):
    return abs(value) > FP16_MAX


@cocotb.test()
async def test_borg_shader_math_batch(dut):
    dut._log.info("Starting Borg ADD/MUL/FMA Integration Test")

    test_data = load_test_data()
    clock = Clock(dut.clk, 100, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)
    driver = BorgDriver(dut, tqv)
    await driver.reset()

    for op in ["add", "mul", "fma"]:
        dut._log.info(f"--- {op.upper()} ---")
        for a, b in test_data["pairs"]:
            if op == "fma":
                for c in [1.0, -0.5]:
                    if driver.is_fp16 and overflows_fp16(a * b + c):
                        continue
                    await run_op_test(dut, driver, op, a, b, c)
            else:
                if driver.is_fp16 and op == "mul" and overflows_fp16(a * b):
                    continue
                await run_op_test(dut, driver, op, a, b)

    dut._log.info("Borg ADD/MUL/FMA Integration Test Passed!")
