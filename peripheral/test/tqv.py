# SPDX-FileCopyrightText: © 2025 Michael Bell
# SPDX-License-Identifier: Apache-2.0

import cocotb
from cocotb.triggers import ClockCycles

from tqv_reg import spi_write_cpha0, spi_read_cpha0

# This class provides access to the peripheral's registers.
# This implementation uses the SPI interface embedded in this project,
# but when the peripheral is added to TinyQV a different implementation
# is used that reads and writes the registers using Risc-V commands:
# https://github.com/MichaelBell/ttsky25a-tinyQV/blob/main/test/tqv.py
class TinyQV:
    def __init__(self, dut, peripheral_num):
        self.dut = dut

    # Reset the design, this reset will initialize TinyQV and connect
    # all inputs and outputs to your peripheral.
    async def reset(self):
        self.dut._log.info("Reset")
        self.dut.ena.value = 1
        self.dut.ui_in.value = 0
        self.dut.uio_in.value = 0
        self.dut.rst_n.value = 0
        await ClockCycles(self.dut.clk, 10)
        self.dut.rst_n.value = 1  
        assert self.dut.uio_oe.value == 0b00001011

    # Write a value to a byte register in your design
    # reg is the address of the register in the range 0-15
    # value is the value to be written, in the range 0-255
    async def write_byte_reg(self, reg, value):
        await spi_write_cpha0(self.dut.clk, self.dut.uio_in, reg, value, 0)

    # Read the value of a byte register from your design
    # reg is the address of the register in the range 0-15
    # The returned value is the data read from the register, in the range 0-255
    async def read_byte_reg(self, reg):
        return await spi_read_cpha0(self.dut.clk, self.dut.uio_in, self.dut.uio_out, 1, reg, 0, 0)

    # Write a value to a half word register in your design
    # reg is the address of the register in the range 0-15
    # value is the value to be written, in the range 0-65535
    async def write_hword_reg(self, reg, value):
        await spi_write_cpha0(self.dut.clk, self.dut.uio_in, reg, value, 1)

    # Read the value of a half word register from your design
    # reg is the address of the register in the range 0-15
    # The returned value is the data read from the register, in the range 0-65535
    async def read_hword_reg(self, reg):
        return await spi_read_cpha0(self.dut.clk, self.dut.uio_in, self.dut.uio_out, 1, reg, 0, 1)

    # Write a value to a word register in your design
    # reg is the address of the register in the range 0-15
    # value is the value to be written
    async def write_word_reg(self, reg, value):
        await spi_write_cpha0(self.dut.clk, self.dut.uio_in, reg, value, 2)

    # Read the value of a word register from your design
    # reg is the address of the register in the range 0-15
    # The returned value is the data read from the register
    async def read_word_reg(self, reg):
        return await spi_read_cpha0(self.dut.clk, self.dut.uio_in, self.dut.uio_out, 1, reg, 0, 2)
    
    # Check whether the user interrupt is asserted
    async def is_interrupt_asserted(self):
        if cocotb.__version__.startswith("2."):
            return self.dut.uio_out.value[0] == 1
        else:
            return self.dut.uio_out[0].value == 1

