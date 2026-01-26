# SPDX-FileCopyrightText: © 2025 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

import cocotb
from cocotb.clock import Clock
from tqv import TinyQV
import struct
import json
import os
import numpy as np


def float_to_bits(f):
    return struct.unpack("<I", struct.pack("<f", f))[0]


def bits_to_float(b):
    return struct.unpack("<f", struct.pack("<I", b & 0xFFFFFFFF))[0]


def load_test_data():
    """
    Climbs from borg_tinyqv/test/user_peripherals/borg/
    to borg_tinyqv/borg_peripheral/data/test_cases.json
    """
    curr_dir = os.path.dirname(os.path.abspath(__file__))
    # Path logic:
    # .. -> user_peripherals
    # .. -> test
    # .. -> borg_tinyqv (root)
    json_path = os.path.join(
        curr_dir, "..", "..", "..", "borg_peripheral", "data", "test_cases.json"
    )

    if not os.path.exists(json_path):
        raise FileNotFoundError(f"Could not find shared test vectors at: {json_path}")

    with open(json_path, "r") as f:
        return json.load(f)


PERIPHERAL_NUM = 39


@cocotb.test()
async def test_borg_float_addition_and_multiplication(dut):
    dut._log.info("Starting Borg Floating Point Addition Test")

    # 1. Load data and setup
    test_data = load_test_data()
    test_pairs = test_data["pairs"]
    EPSILON = test_data["epsilon"]

    clock = Clock(dut.clk, 100, unit="ns")
    cocotb.start_soon(clock.start())
    tqv = TinyQV(dut, PERIPHERAL_NUM)
    await tqv.reset()

    # Unified Address Map
    ADDR_A, ADDR_B, ADDR_ADD, ADDR_MUL = 0, 4, 8, 12

    # 2. Iterate through shared test cases
    for a, b in test_pairs:
        # Cast to float32 to ensure Python's expected value matches hardware precision
        a_32 = np.float32(a)
        b_32 = np.float32(b)

        expected_sum = a_32 + b_32
        expected_mul = a_32 * b_32

        # Write Operands
        await tqv.write_word_reg(ADDR_A, float_to_bits(a_32))
        await tqv.write_word_reg(ADDR_B, float_to_bits(b_32))

        # Check Addition
        add_res = bits_to_float(await tqv.read_word_reg(ADDR_ADD))
        assert (
            abs(add_res - expected_sum) < EPSILON
        ), f"Add failed: {a_32} + {b_32} = {add_res} (Expected {expected_sum})"

        # Check Multiplication
        mul_res = bits_to_float(await tqv.read_word_reg(ADDR_MUL))
        assert (
            abs(mul_res - expected_mul) < EPSILON
        ), f"Mul failed: {a_32} * {b_32} = {mul_res} (Expected {expected_mul})"

        dut._log.info(f"Passed: {a_32} and {b_32}")

    # 3. Final sanity check
    read_bits_a = await tqv.read_word_reg(ADDR_A)
    last_val_a = np.float32(test_pairs[-1][0])
    assert read_bits_a == float_to_bits(last_val_a), "Operand A corrupted!"

    dut._log.info("Borg Floating Point Addition and Multiplication Test Passed!")
