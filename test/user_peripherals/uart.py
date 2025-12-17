# SPDX-FileCopyrightText: © 2024 Tiny Tapeout
# SPDX-License-Identifier: Apache-2.0

import random
import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles, Timer

from tqv import TinyQV

PERIPHERAL_NUM = 2

async def expect_byte(dut, uart_byte, tx_pin=None, index=None, bit_time=8680):
    """
    Expects a UART byte.
    tx_pin can be a single signal (handle) or a bus (handle).
    If a bus is passed, index must be provided.
    """
    if tx_pin is None:
        tx_pin = dut.uart_tx

    # Helper to get current bit value regardless of if it's a bus or single pin
    get_val = lambda: tx_pin.value if index is None else tx_pin.value[index]

    await Timer(bit_time // 2, "ns")
    assert get_val() == 0, f"Start bit not 0 at {cocotb.utils.get_sim_time('ns')}ns"

    for i in range(8):
        await Timer(bit_time, "ns")
        assert get_val() == (uart_byte & 1), f"Data bit {i} incorrect"
        uart_byte >>= 1

    await Timer(bit_time, "ns")
    assert get_val() == 1, "Stop bit not 1"

    await Timer(bit_time // 2, "ns")
    assert get_val() == 1, "Idle bit not 1"

async def send_byte(dut, val, check_rts=1, rx_pin=None, rx_index=None, rts_pin=None, rts_index=None, bit_time=8680):
    """
    Sends a UART byte.
    rx_pin/rts_pin can be single signals or buses. If bus, index must be provided.
    """
    if rx_pin is None:
        rx_pin = dut.uart_rx
    if rts_pin is None:
        rts_pin = dut.uart_rts

    get_rts = lambda: rts_pin.value if rts_index is None else rts_pin.value[rts_index]

    # Helper function to drive the RX pin (handles both single bit and bus bit)
    def set_rx(bit):
        if rx_index is None:
            rx_pin.value = bit
        else:
            # Create a new LogicArray based on current bus value and modify one bit
            new_val = rx_pin.value
            new_val[rx_index] = bit
            rx_pin.value = new_val

    if check_rts != 0:
        assert get_rts() == 0, "RTS should be low before start"

    # Start bit
    set_rx(0)
    await Timer(bit_time, "ns")

    for i in range(8):
        set_rx(val & 1)
        await Timer(bit_time, "ns")
        if check_rts != 0:
            assert get_rts() == check_rts - 1
        val >>= 1

    # Stop bit
    set_rx(1)
    await Timer(bit_time, "ns")
    if check_rts != 0:
        assert get_rts() == check_rts - 1

@cocotb.test()
async def test_basic(dut):
    dut._log.info("Start")

    # Set the clock frequency to 64MHz
    clock = Clock(dut.clk, 15.624, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)

    # Reset
    await tqv.reset(initial_ui_in=0x80)

    dut._log.info("UART basic TX and RX")

    # Test sending several bytes (Default pins)
    for i in range(5):
        val = random.randint(0, 255)
        await tqv.write_byte_reg(0, val)
        await expect_byte(dut, val)

    # Test receiving several bytes (Default pins)
    for i in range(5):
        val = random.randint(0, 255)
        await send_byte(dut, val)
        assert await tqv.read_byte_reg(0) == val

    # Test rts goes low if a second byte sent without reading the first
    val = random.randint(0, 255)
    val2 = random.randint(0, 255)
    await send_byte(dut, val)
    await send_byte(dut, val2, check_rts=2)
    assert await tqv.read_byte_reg(0) == val
    assert await tqv.read_byte_reg(0) == val2

    # Check TX is sent on every even pin (Pass bus + index)
    for i in range(0, 8, 2):
        val = random.randint(0, 255)
        await tqv.write_byte_reg(0, val)
        await expect_byte(dut, val, tx_pin=dut.uo_out, index=i)

    # Check RTS is sent on every odd pin (Pass bus + index)
    for i in range(1, 8, 2):
        val = random.randint(0, 255)
        val2 = random.randint(0, 255)
        await send_byte(dut, val, rts_pin=dut.uo_out, rts_index=i)
        await send_byte(dut, val2, check_rts=2, rts_pin=dut.uo_out, rts_index=i)
        assert await tqv.read_byte_reg(0) == val
        assert await tqv.read_byte_reg(0) == val2

    # Check alternative RX pin
    assert await tqv.read_byte_reg(0xc) == 0
    # To modify one bit of a packed bus handle, we must update the whole .value
    temp_ui = dut.ui_in.value
    temp_ui[3] = 1
    dut.ui_in.value = temp_ui 
    
    await tqv.write_byte_reg(0xc, 1)
    assert await tqv.read_byte_reg(0xc) == 1

    val = random.randint(0, 255)
    val2 = random.randint(0, 255)
    
    # Pass ui_in as the bus and 3 as the index
    await send_byte(dut, val, rx_pin=dut.ui_in, rx_index=3)
    await send_byte(dut, val2, check_rts=2, rx_pin=dut.ui_in, rx_index=3)
    assert await tqv.read_byte_reg(0) == val
    assert await tqv.read_byte_reg(0) == val2

@cocotb.test()
async def test_divider(dut):
    dut._log.info("Start")

    # Set the clock frequency to 64MHz
    clock = Clock(dut.clk, 15.624, unit="ns")
    cocotb.start_soon(clock.start())

    tqv = TinyQV(dut, PERIPHERAL_NUM)

    # Reset
    await tqv.reset(initial_ui_in=0x80)

    for baud in (9600, 1000000, 57600):
        divider = 64000000 // baud
        bit_time = 1000000000 // baud
        dut._log.info(f"Test {baud} baud, divider {divider}")

        # Set up divider
        await tqv.write_word_reg(0x8, divider)
        assert await tqv.read_word_reg(0x8) == divider

        # Test UART TX
        val = 0x54
        await tqv.write_byte_reg(0, val, sync=False)
        await expect_byte(dut, val, bit_time=bit_time)

        # Test UART RX
        for j in range(3):
            val = random.randint(0, 255)
            val2 = random.randint(0, 255)
            await send_byte(dut, val, bit_time=bit_time)
            await send_byte(dut, val2, check_rts=2, bit_time=bit_time)
            assert await tqv.read_byte_reg(0) == val
            assert await tqv.read_byte_reg(0) == val2
