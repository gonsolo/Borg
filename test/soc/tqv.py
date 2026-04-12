from cocotb.triggers import ClockCycles

from riscvmodel.insn import *
from riscvmodel.regnames import x0, tp, a0, a1
from riscvmodel import csrnames

import test_util
from borg_mmio import USER_PERI_STRIDE

# Maximum value for a 12-bit signed immediate.
_IMM12_MAX = 2047
_IMM12_MIN = -2048

# This class provides access to the peripheral's registers.
class TinyQV:

    # The peripheral number must be provided.
    def __init__(self, dut, peripheral_num):
        self.dut = dut
        self.peripheral_num = peripheral_num
        stride = USER_PERI_STRIDE
        if peripheral_num < 16:
            self.base_address = peripheral_num * stride
        elif peripheral_num >= 32:
            self.base_address = peripheral_num * stride - 8 * stride
        else:
            self.base_address = 12 * stride + peripheral_num * 0x10

    async def _emit_load_base(self, reg_offset):
        """Load (tp + self.base_address + reg_offset) into a0 using ADDI chain.

        When the total offset fits in a single 12-bit signed immediate we
        emit one ADDI; otherwise we chain multiple ADDIs each ≤ 2047.
        Returns the base register (a0) and the remaining offset (always 0).
        """
        total = self.base_address + reg_offset
        if _IMM12_MIN <= total <= _IMM12_MAX:
            # Fits — just return tp and use the offset directly.
            return tp, total

        # Build in a0 via chained ADDIs from tp.
        remaining = total
        first = True
        while remaining != 0:
            step = min(remaining, _IMM12_MAX) if remaining > 0 else max(remaining, _IMM12_MIN)
            src = tp if first else a0
            await test_util.send_instr(self.dut, InstructionADDI(a0, src, step).encode())
            remaining -= step
            first = False

        if first:
            # total was 0 — just copy tp
            await test_util.send_instr(self.dut, InstructionADDI(a0, tp, 0).encode())

        return a0, 0

    # Reset the design, this reset will initialize TinyQV and connect
    # all inputs and outputs to your peripheral.
    async def reset(self, initial_ui_in=0):
        # Ensure any previously running test is cleaned up
        await test_util.stop_nops()

        await test_util.reset(self.dut, 1, initial_ui_in)

        # Should start reading flash after 1 cycle
        await ClockCycles(self.dut.clk, 1)
        await test_util.start_read(self.dut, 0)
        
        await test_util.set_all_outputs_to_peripheral(self.dut, self.peripheral_num)

        await test_util.start_nops(self.dut)

    # Write a value to a byte register in your design
    # reg is the address of the register in the range 0-15
    # value is the value to be written, in the range 0-255
    # If sync is false this function will return before the store is completed.
    async def write_reg(self, reg, value, sync=True):
        value = int(value)
        await test_util.stop_nops()
        base, off = await self._emit_load_base(reg)
        await test_util.send_instr(self.dut, InstructionADDI(a1, x0, value).encode())
        await test_util.send_instr(self.dut, InstructionSB(base, a1, off).encode())

        if sync:
            # Read a register in order to ensure the store is complete before returning
            assert await test_util.read_reg(self.dut, a1) == value

        await test_util.start_nops(self.dut)

    # Read the value of a byte register from your design
    # reg is the address of the register in the range 0-15
    # The returned value is the data read from the register, in the range 0-255
    async def read_reg(self, reg):
        await test_util.stop_nops()
        base, off = await self._emit_load_base(reg)
        await test_util.send_instr(self.dut, InstructionLBU(a1, base, off).encode())
        val = await test_util.read_reg(self.dut, a1, True)
        await test_util.start_nops(self.dut)
        return val

    # Write a value to a byte register in your design
    # reg is the address of the register in the range 0-15
    # value is the value to be written, in the range 0-255
    # If sync is false this function will return before the store is completed.
    async def write_byte_reg(self, reg, value, sync=True):
        await self.write_reg(reg, value, sync)

    # Read the value of a byte register from your design
    # reg is the address of the register in the range 0-15
    # The returned value is the data read from the register, in the range 0-255
    async def read_byte_reg(self, reg):
        return await self.read_reg(reg)

    # Write a value to a half word register in your design
    # reg is the address of the register in the range 0-15
    # value is the value to be written, in the range 0-65535
    # If sync is false this function will return before the store is completed.
    async def write_hword_reg(self, reg, value, sync=True):
        value = int(value)
        await test_util.stop_nops()
        # Prepare value for LUI + ADDI
        value_upper = (value + 0x800) >> 12
        value_lower = value & 0xfff
        if value_lower >= 0x800:
            value_lower -= 0x1000

        await test_util.send_instr(self.dut, InstructionLUI(a1, value_upper).encode())
        await test_util.send_instr(self.dut, InstructionADDI(a1, a1, value_lower).encode())
        base, off = await self._emit_load_base(reg)
        await test_util.send_instr(self.dut, InstructionSH(base, a1, off).encode())

        if sync:
            # Read a register in order to ensure the store is complete before returning
            assert await test_util.read_reg(self.dut, a1) == value

        await test_util.start_nops(self.dut)

    # Read the value of a half word register from your design
    # reg is the address of the register in the range 0-15
    # The returned value is the data read from the register, in the range 0-65535
    async def read_hword_reg(self, reg):
        await test_util.stop_nops()
        base, off = await self._emit_load_base(reg)
        await test_util.send_instr(self.dut, InstructionLHU(a1, base, off).encode())
        val = await test_util.read_reg(self.dut, a1, True)
        await test_util.start_nops(self.dut)
        return val

    # Write a value to a word register in your design
    # reg is the address of the register in the range 0-15
    # value is the value to be written
    # If sync is false this function will return before the store is completed.
    async def write_word_reg(self, reg, value, sync=True):
        value = int(value)
        await test_util.stop_nops()

        # Prepare value for LUI + ADDI
        value_upper = ((value + 0x800) >> 12) & 0xfffff
        value_lower = value & 0xfff
        if value_lower >= 0x800:
            value_lower -= 0x1000

        await test_util.send_instr(self.dut, InstructionLUI(a1, value_upper).encode())
        await test_util.send_instr(self.dut, InstructionADDI(a1, a1, value_lower).encode())
        base, off = await self._emit_load_base(reg)
        await test_util.send_instr(self.dut, InstructionSW(base, a1, off).encode())

        if sync:
            # Read a register in order to ensure the store is complete before returning
            assert await test_util.read_reg(self.dut, a1) == value

        await test_util.start_nops(self.dut)

    # Read the value of a word register from your design
    # reg is the address of the register in the range 0-15
    # The returned value is the data read from the register
    async def read_word_reg(self, reg):
        await test_util.stop_nops()
        base, off = await self._emit_load_base(reg)
        await test_util.send_instr(self.dut, InstructionLW(a1, base, off).encode())
        val = await test_util.read_reg(self.dut, a1, True)
        await test_util.start_nops(self.dut)
        return val

    # Check whether the user interrupt is asserted
    async def is_interrupt_asserted(self):
        await test_util.stop_nops()
        await test_util.send_instr(self.dut, InstructionCSRRS(a1, x0, csrnames.mip).encode())
        val = await test_util.read_reg(self.dut, a1)
        await test_util.start_nops(self.dut)
        return (val & (1 << (16 + self.peripheral_num))) != 0
