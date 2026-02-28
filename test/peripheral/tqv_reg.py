# Copyright (c) 2024 Caio Alonso da Costa
# SPDX-License-Identifier: Apache-2.0

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles


def get_int(value):
    if cocotb.__version__.startswith("2."):
        # Get the string representation of the LogicArray
        value_str = str(value)

        # Convert to lowercase to check for both 'X'/'x' and 'Z'/'z'
        lower_str = value_str.lower()

        # Check if the value contains any non-binary characters
        if "x" in lower_str or "z" in lower_str:
            # Replace all non-binary characters with '0' for safe int conversion
            clean_binstr = lower_str.replace("x", "0").replace("z", "0")
            return int(clean_binstr, 2)
        else:
            # If the value is clean (only 0s and 1s), use the standard cocotb method
            return value.to_unsigned()
    else:
        # For cocotb 1.x
        return value


# def get_int(value):
#    if cocotb.__version__.startswith("2."):
#        return value.to_unsigned()
#    else:
#        return value


def get_bit(value, bit_index):
    temp = value & (1 << bit_index)
    return temp


def set_bit(value, bit_index):
    temp = value | (1 << bit_index)
    return temp


def clear_bit(value, bit_index):
    temp = value & ~(1 << bit_index)
    return temp


def xor_bit(value, bit_index):
    temp = value ^ (1 << bit_index)
    return temp


def pull_cs_high(value):
    temp = set_bit(value, 4)
    return temp


def pull_cs_low(value):
    temp = clear_bit(value, 4)
    return temp


def spi_clk_high(value):
    temp = set_bit(value, 5)
    return temp


def spi_clk_low(value):
    temp = clear_bit(value, 5)
    return temp


def spi_clk_invert(value):
    temp = xor_bit(value, 5)
    return temp


def spi_mosi_high(value):
    temp = set_bit(value, 6)
    return temp


def spi_mosi_low(value):
    temp = clear_bit(value, 6)
    return temp


def spi_miso_read(port):
    return get_bit(get_int(port.value), 3) >> 3


SPI_HALF_CYCLE_DELAY = 2


async def spi_write_cpha0(clk, port, address, data, width):

    temp = get_int(port.value)
    result = pull_cs_high(temp)
    port.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

    # Pull CS low + Write command bit - bit 31 - MSBIT in first word
    temp = get_int(port.value)
    result = pull_cs_low(temp)
    result2 = spi_mosi_high(result)
    port.value = result2
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
    temp = get_int(port.value)
    result = spi_clk_invert(temp)
    port.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

    # Next two bits indicate txn width
    iterator = 1
    while iterator >= 0:
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        address_bit = get_bit(width, iterator)
        if address_bit == 0:
            result2 = spi_mosi_low(result)
        else:
            result2 = spi_mosi_high(result)
        port.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        port.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        iterator -= 1

    iterator = 0
    while iterator < 23:
        # Don't care - bits 28-6
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        result2 = spi_mosi_low(result)
        port.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        port.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        iterator += 1

    iterator = 5
    while iterator >= 0:
        # Address[iterator] - bits 5-0
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        address_bit = get_bit(address, iterator)
        if address_bit == 0:
            result2 = spi_mosi_low(result)
        else:
            result2 = spi_mosi_high(result)
        port.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        port.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        iterator -= 1

    iterator = 31
    while iterator >= 0:
        # Data[iterator]
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        data_bit = get_bit(data, iterator)
        if data_bit == 0:
            result2 = spi_mosi_low(result)
        else:
            result2 = spi_mosi_high(result)
        port.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port.value)
        result = spi_clk_invert(temp)
        port.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        iterator -= 1

    temp = get_int(port.value)
    result = spi_clk_invert(temp)
    port.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

    temp = get_int(port.value)
    result = pull_cs_high(temp)
    port.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)


async def spi_read_cpha0(clk, port_in, port_out, data_ready_bit, address, data, width):

    temp = get_int(port_in.value)
    result = pull_cs_high(temp)
    port_in.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

    # Pull CS low + Read command bit - bit 7 - MSBIT in first byte
    temp = get_int(port_in.value)
    result = pull_cs_low(temp)
    result2 = spi_mosi_low(result)
    port_in.value = result2
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
    temp = get_int(port_in.value)
    result = spi_clk_invert(temp)
    port_in.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

    # Next two bits indicate txn width
    iterator = 1
    while iterator >= 0:
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        address_bit = get_bit(width, iterator)
        if address_bit == 0:
            result2 = spi_mosi_low(result)
        else:
            result2 = spi_mosi_high(result)
        port_in.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        port_in.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        iterator -= 1

    iterator = 0
    while iterator < 23:
        # Don't care - bits 28-6
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        result2 = spi_mosi_low(result)
        port_in.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        port_in.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        iterator += 1

    iterator = 5
    while iterator >= 0:
        # Address[iterator] - bits 5-0
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        address_bit = get_bit(address, iterator)
        if address_bit == 0:
            result2 = spi_mosi_low(result)
        else:
            result2 = spi_mosi_high(result)
        port_in.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        port_in.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        iterator -= 1

    miso_byte = 0
    miso_bit = 0

    await ClockCycles(clk, 1)
    data_ready_delay = 0
    while get_bit(get_int(port_out.value), data_ready_bit) == 0:
        data_ready_delay += 1
        assert data_ready_delay < 100
        await ClockCycles(clk, 1)

    # WAIT fully for spi_reg to transition to STATE_TX_DATA.
    # data_ready becomes 1 -> tt_wrapper transitions to STATE_DATA
    # -> data_valid becomes 1 -> spi_reg transitions to STATE_TX_DATA.
    # This takes 3-4 clock cycles in the hardware.
    await ClockCycles(clk, 5)

    iterator = 31
    while iterator >= 0:
        # Data[iterator]
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        data_bit = get_bit(data, iterator)
        if data_bit == 0:
            result2 = spi_mosi_low(result)
        else:
            result2 = spi_mosi_high(result)
        port_in.value = result2
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)
        temp = get_int(port_in.value)
        result = spi_clk_invert(temp)
        port_in.value = result
        await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

        miso_bit = spi_miso_read(port_out)
        miso_byte = miso_byte | (miso_bit << iterator)
        iterator -= 1

    temp = get_int(port_in.value)
    result = spi_clk_invert(temp)
    port_in.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

    temp = get_int(port_in.value)
    result = pull_cs_high(temp)
    port_in.value = result
    await ClockCycles(clk, SPI_HALF_CYCLE_DELAY)

    return miso_byte
