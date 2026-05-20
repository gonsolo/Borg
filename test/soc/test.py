# SPDX-FileCopyrightText: © 2024 Michael Bell
# SPDX-License-Identifier: MIT

import os
import random

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles, Timer, FallingEdge, RisingEdge
from cocotb.utils import get_sim_time

from riscvmodel.insn import *

from riscvmodel.regnames import x0, x1, sp, gp, tp, a0, a1, a2, a3, a4
from riscvmodel.variant import RV32E

from borg_mmio import (
    SOC_PERI_ID_OFFSET, SOC_PERI_GPIO_OUT_SEL_OFFSET,
    USER_BASE, GPIO_BASE, GPIO_GPIO_OUT_OFFSET, GPIO_GPIO_IN_OFFSET, GPIO_FUNC_SEL_BIT,
    UART_BASE, UART_TX_RX_OFFSET, UART_STATUS_OFFSET,
)

GPIO_TP_BASE = GPIO_BASE - USER_BASE
UART_TP_BASE = UART_BASE - USER_BASE

from test_util import (
    reset,
    start_read,
    boot_cpu,
    send_instr,
    start_nops,
    stop_nops,
    read_byte,
    read_reg,
    load_reg,
    expect_load,
    expect_store,
)

CLOCK_MHZ = int(os.environ.get("CLOCK_MHZ", "4"))
print(f"DEBUG: CLOCK_MHZ = {CLOCK_MHZ}")
# Cocotb requires clock period to be divisible by 2.
# 1000000.0 ps / 12 MHz = 83333.333... ps
# We use an even integer picoseconds period closest to the real period.
CLOCK_PERIOD_PS = int(1000000.0 / CLOCK_MHZ)
if CLOCK_PERIOD_PS % 2 != 0:
    CLOCK_PERIOD_PS += 1
print(f"DEBUG: CLOCK_PERIOD_PS = {CLOCK_PERIOD_PS}")

@cocotb.test(skip=True)
async def test_start(dut):
    dut._log.info("Start")

    clock = Clock(dut.clk, CLOCK_PERIOD_PS, unit="ps")
    cocotb.start_soon(clock.start())

    # Reset
    await reset(dut)

    # Should start reading flash after 1 cycle
    await ClockCycles(dut.clk, 1)
    await start_read(dut, 0)
    await boot_cpu(dut)

    # Read ID
    await send_instr(dut, InstructionLW(x1, tp, SOC_PERI_ID_OFFSET).encode())
    assert await read_reg(dut, x1) == ord("A")

    for i in range(8):
        await send_instr(dut, InstructionADDI(i + 8, x0, 0x102 * i).encode())

    # Test UART TX
    # UART_TP_BASE (0x800 = 2048) exceeds 12-bit signed immediate.
    # Build UART base in a2 = tp + 0x400 + 0x400.
    await send_instr(dut, InstructionADDI(a2, tp, 0x400).encode())
    await send_instr(dut, InstructionADDI(a2, a2, 0x400).encode())
    uart_byte = 0x54
    await send_instr(dut, InstructionADDI(x1, x0, uart_byte).encode())
    await send_instr(dut, InstructionSW(a2, x1, UART_TX_RX_OFFSET).encode())

    await start_nops(dut)
    
    # Compute bit time in integer picoseconds to avoid FP precision errors
    baud_divider = (CLOCK_MHZ * 1000000) // 115200
    # Hardware counts 0..baud_divider (baud_divider+1 cycles per bit)
    bit_time_ps = (baud_divider + 1) * CLOCK_PERIOD_PS  # exact integer

    # Wait for start bit edge (robust to GL propagation delay)
    timeout_ns = 100000
    start_sim_time = get_sim_time("ns")
    while dut.uart_tx.value == 1:
        await Timer(5, "ns")
        if get_sim_time("ns") - start_sim_time > timeout_ns:
            assert False, "Timeout waiting for UART TX start bit"

    # Sample mid-bit: wait 1.5 bit times from start bit edge
    await Timer(bit_time_ps * 3 // 2, "ps")
    assert dut.uart_tx.value == (uart_byte & 1)
    uart_byte >>= 1
    for i in range(7):
        await Timer(bit_time_ps, "ps")
        assert dut.uart_tx.value == (uart_byte & 1)
        uart_byte >>= 1
    await Timer(bit_time_ps, "ps")
    assert dut.uart_tx.value == 1

    # Test UART RX
    for j in range(5):
        assert dut.uart_rts.value == 0

        uart_rx_byte = random.randint(0, 255)
        val = uart_rx_byte
        dut.uart_rx.value = 0
        await Timer(bit_time_ps, "ps")
        for i in range(8):
            dut.uart_rx.value = val & 1
            await Timer(bit_time_ps, "ps")
            assert dut.uart_rts.value == 0
            val >>= 1
        dut.uart_rx.value = 1
        await Timer(bit_time_ps, "ps")
        assert dut.uart_rts.value == 0

        uart_rx_byte2 = random.randint(0, 255)
        val = uart_rx_byte2
        dut.uart_rx.value = 0
        await Timer(bit_time_ps, "ps")
        for i in range(8):
            dut.uart_rx.value = val & 1
            await Timer(bit_time_ps, "ps")
            assert dut.uart_rts.value == 1
            val >>= 1
        dut.uart_rx.value = 1
        await Timer(bit_time_ps, "ps")
        assert dut.uart_rts.value == 1

        await stop_nops()

        await send_instr(dut, InstructionLW(x1, a2, UART_STATUS_OFFSET).encode())
        await read_byte(dut, x1, 0x2)
        await send_instr(dut, InstructionLW(x1, a2, UART_TX_RX_OFFSET).encode())
        await read_byte(dut, x1, uart_rx_byte)
        assert dut.uart_rts.value == 0
        await send_instr(dut, InstructionLW(x1, a2, UART_STATUS_OFFSET).encode())
        await read_byte(dut, x1, 0x2)
        await send_instr(dut, InstructionLW(x1, a2, UART_TX_RX_OFFSET).encode())
        await read_byte(dut, x1, uart_rx_byte2)
        assert dut.uart_rts.value == 0
        await send_instr(dut, InstructionLW(x1, a2, UART_STATUS_OFFSET).encode())
        await read_byte(dut, x1, 0)

        if j != 4:
            await start_nops(dut)

    # Test Debug UART TX
    uart_byte = 0x5A
    await send_instr(dut, InstructionADDI(x1, x0, uart_byte).encode())
    await read_byte(dut, x1, uart_byte)

    # GPIO
    await send_instr(dut, InstructionADDI(x1, x0, 0xFF).encode())
    await send_instr(dut, InstructionSW(tp, x1, SOC_PERI_GPIO_OUT_SEL_OFFSET).encode())

    for i in range(40):
        gpio_sel = random.randint(0, 255)
        gpio_out = random.randint(0, 255)
        for j in range(8):
            await send_instr(dut, InstructionADDI(x1, x0, (gpio_sel >> j) & 1).encode())
            await send_instr(dut, InstructionSW(tp, x1, GPIO_TP_BASE + (1 << GPIO_FUNC_SEL_BIT) + j * 4).encode())
        await send_instr(dut, InstructionADDI(x1, x0, gpio_out).encode())
        await send_instr(dut, InstructionSW(tp, x1, GPIO_TP_BASE + GPIO_GPIO_OUT_OFFSET).encode())
        for _ in range(5):
            await send_instr(dut, InstructionADDI(x0, x0, 0).encode())
        assert (dut.uo_out.value.to_unsigned() & gpio_sel) == (gpio_out & gpio_sel)

    # Ensure uo_out is normally high
    await send_instr(dut, InstructionADDI(x1, x0, 0x80).encode())
    await send_instr(dut, InstructionSW(tp, x1, GPIO_TP_BASE + GPIO_GPIO_OUT_OFFSET).encode())




async def test_pwm(dut, pwm_value, pwm_strobe):

    dut._log.info(f"sync to PWM rising edge")
    # sync to pwm gen
    await RisingEdge(dut.qspi_ram_b_select)
    await FallingEdge(dut.qspi_ram_b_select)
    await RisingEdge(dut.qspi_ram_b_select)
    await ClockCycles(dut.clk, 1)
    dut._log.info(f"assert {pwm_strobe * pwm_value} clocks of PWM high")
    # assert the PWM is on for the length of time
    for i in range(pwm_value):
        assert dut.uio_out.value[7] == 1, f"failed on cycle {i}"
        await ClockCycles(dut.clk, pwm_strobe)
    dut._log.info(f"assert {pwm_strobe * (255 - pwm_value)} clocks of PWM low")
    for i in range(255 - pwm_value):
        assert dut.uio_out.value[7] == 0, f"failed on cycle {i}"
        await ClockCycles(dut.clk, pwm_strobe)


@cocotb.test(skip=True)
async def test_load_bug(dut):
    dut._log.info("Start")

    clock = Clock(dut.clk, CLOCK_PERIOD_PS, unit="ps")
    cocotb.start_soon(clock.start())

    # Reset
    await reset(dut)

    input_byte = 0b01101000
    dut.ui_in.value = input_byte

    def encode_clwsp(reg, imm):
        scrambled = (
            ((imm << (12 - 5)) & 0b1000000000000)
            | ((imm << (4 - 2)) & 0b0000001110000)
            | ((imm >> (6 - 2)) & 0b0000000001100)
        )
        return 0x4002 | scrambled | (reg << 7)

    # Should start reading flash after 1 cycle
    await ClockCycles(dut.clk, 1)
    await start_read(dut, 0)
    await boot_cpu(dut)
    await send_instr(dut, InstructionLUI(sp, 0x1001).encode())  # sp = 0x01001000 (PC-independent)
    await send_instr(dut, InstructionADDI(a0, x0, 0x001).encode())
    await send_instr(dut, InstructionBEQ(a0, x0, 270).encode())

    # Use standard LW for tp-based load (LWTP was removed)
    await send_instr(dut, InstructionLW(a3, tp, GPIO_TP_BASE + GPIO_GPIO_IN_OFFSET).encode())
    await send_instr(dut, InstructionLW(a2, sp, 12).encode())
    await expect_load(dut, 0x1001000 + 12, 0x123)
    await read_byte(dut, a3, input_byte)
    await read_byte(dut, a2, 0x123)


@cocotb.test(skip=True)
async def test_load_throughput(dut):
    dut._log.info("Start")

    clock = Clock(dut.clk, CLOCK_PERIOD_PS, unit="ps")
    cocotb.start_soon(clock.start())

    # Reset
    await reset(dut)

    input_byte = 0b01101000
    dut.ui_in.value = input_byte

    def encode_clwsp(reg, imm):
        scrambled = (
            ((imm << (12 - 5)) & 0b1000000000000)
            | ((imm << (4 - 2)) & 0b0000001110000)
            | ((imm >> (6 - 2)) & 0b0000000001100)
        )
        return 0x4002 | scrambled | (reg << 7)

    # Should start reading flash after 1 cycle
    await ClockCycles(dut.clk, 1)
    await start_read(dut, 0)
    await boot_cpu(dut)
    await send_instr(dut, InstructionLUI(sp, 0x1001).encode())  # sp = 0x01001000 (PC-independent)
    await send_instr(dut, InstructionADDI(a0, x0, 0x001).encode())
    await send_instr(dut, InstructionBEQ(a0, x0, 270).encode())

    for i in range(32):
        await send_instr(dut, InstructionLW(a2, sp, i * 4).encode())
        # Use standard SW for tp-based store (SWTP was removed)
        await send_instr(dut, InstructionSW(tp, a2, 0x3C0).encode())
        await expect_load(dut, 0x1001000 + i * 4, i)


### Random operation testing ###
reg = [0] * 16


# Each Op does reg[d] = fn(a, b)
# fn will access reg global array
class SimpleOp:
    def __init__(self, rvm_insn, fn, name):
        self.rvm_insn = rvm_insn
        self.fn = fn
        self.name = name
        self.is_mem_op = False

    def randomize(self):
        self.rvm_insn_inst = self.rvm_insn()
        self.rvm_insn_inst.randomize(variant=RV32E)

    def execute_fn(self, rd, rs1, arg2):
        if rd != 0 and rd != 3 and rd != 4:
            reg[rd] = self.fn(rs1, arg2)
            while reg[rd] < -0x80000000:
                reg[rd] += 0x100000000
            while reg[rd] > 0x7FFFFFFF:
                reg[rd] -= 0x100000000

    def encode(self, rd, rs1, arg2):
        return self.rvm_insn(rd, rs1, arg2).encode()

    def get_valid_rd(self):
        return random.randint(0, 15)

    def get_valid_rs1(self):
        return random.randint(0, 15)

    def get_valid_arg2(self):
        return (
            random.randint(0, 15)
            if issubclass(self.rvm_insn, InstructionRType)
            else (
                self.rvm_insn_inst.shamt.value
                if issubclass(self.rvm_insn, InstructionISType)
                else self.rvm_insn_inst.imm.value
            )
        )


def encode_ci(reg, imm, opcode):
    scrambled = ((imm << (12 - 5)) & 0b1000000000000) | (
        (imm << (2 - 0)) & 0b0000001111100
    )
    return opcode | scrambled | (reg << 7)


def encode_cli(reg, imm):
    return encode_ci(reg, imm, 0x4001)


def encode_caddi(reg, imm):
    return encode_ci(reg, imm, 0x0001)


def encode_cslli(reg, imm):
    return encode_ci(reg, imm, 0x0002)


def encode_ci2(reg, imm, opcode):
    return encode_ci(reg - 8, imm, opcode)


def encode_csrli(reg, imm):
    return encode_ci2(reg, imm, 0x8001)


def encode_csrai(reg, imm):
    return encode_ci2(reg, imm, 0x8401)


def encode_candi(reg, imm):
    return encode_ci2(reg, imm, 0x8801)


def encode_cnot(reg, _):
    return 0x9C75 | ((reg - 8) << 7)


def encode_czext_b(reg, _):
    return 0x9C61 | ((reg - 8) << 7)


def encode_czext_h(reg, _):
    return 0x9C69 | ((reg - 8) << 7)


def encode_cr(dest_reg, src_reg, opcode):
    return opcode | (dest_reg << 7) | (src_reg << 2)


def encode_cmv(dest_reg, src_reg):
    return encode_cr(dest_reg, src_reg, 0x8002)


def encode_cadd(dest_reg, src_reg):
    return encode_cr(dest_reg, src_reg, 0x9002)


def encode_ca(dest_reg, src_reg, opcode):
    return opcode | ((dest_reg - 8) << 7) | ((src_reg - 8) << 2)


def encode_csub(dest_reg, src_reg):
    return encode_ca(dest_reg, src_reg, 0x8C01)


def encode_cxor(dest_reg, src_reg):
    return encode_ca(dest_reg, src_reg, 0x8C21)


def encode_cor(dest_reg, src_reg):
    return encode_ca(dest_reg, src_reg, 0x8C41)


def encode_cand(dest_reg, src_reg):
    return encode_ca(dest_reg, src_reg, 0x8C61)


class CIOp:
    def __init__(self, encoder, min_rs1, min_imm, fn, name):
        self.encoder = encoder
        self.fn = fn
        self.name = name
        self.min_rs1 = min_rs1
        self.min_imm = min_imm
        self.is_mem_op = False

    def randomize(self):
        self.rs1 = random.randint(self.min_rs1, 15)
        self.imm = random.randint(self.min_imm, 31)

    def execute_fn(self, rd, rs1, arg2):
        if rd != 0 and rd != 3 and rd != 4:
            reg[rd] = self.fn(rs1, arg2)
            while reg[rd] < -0x80000000:
                reg[rd] += 0x100000000
            while reg[rd] > 0x7FFFFFFF:
                reg[rd] -= 0x100000000

    def encode(self, rd, rs1, arg2):
        return self.encoder(rs1, arg2)

    def get_valid_rd(self):
        return self.rs1

    def get_valid_rs1(self):
        return self.rs1

    def get_valid_arg2(self):
        return self.imm


class CROp:
    def __init__(self, encoder, min_reg, fn, name):
        self.encoder = encoder
        self.fn = fn
        self.name = name
        self.min_reg = min_reg
        self.is_mem_op = False

    def randomize(self):
        self.rs1 = random.randint(self.min_reg, 15)
        self.rs2 = random.randint(self.min_reg, 15)

    def execute_fn(self, rd, rs1, arg2):
        if rd != 0 and rd != 3 and rd != 4:
            reg[rd] = self.fn(rs1, arg2)
            while reg[rd] < -0x80000000:
                reg[rd] += 0x100000000
            while reg[rd] > 0x7FFFFFFF:
                reg[rd] -= 0x100000000

    def encode(self, rd, rs1, arg2):
        return self.encoder(rs1, arg2)

    def get_valid_rd(self):
        return self.rs1

    def get_valid_rs1(self):
        return self.rs1

    def get_valid_arg2(self):
        return self.rs2


class InstructionCZERO_EQZ(InstructionRType):
    def __init__(self, rd=None, rs1=None, rs2=None):
        self.rd = rd
        self.rs1 = rs1
        self.rs2 = rs2
        self.op = 5

    def execute(self, model):
        pass

    def encode(self):
        return (
            (0b0000111 << 25)
            | (self.rs2 << 20)
            | (self.rs1 << 15)
            | (self.op << 12)
            | (self.rd << 7)
            | 0b0110011
        )


class InstructionCZERO_NEZ(InstructionRType):
    def __init__(self, rd=None, rs1=None, rs2=None):
        self.rd = rd
        self.rs1 = rs1
        self.rs2 = rs2
        self.op = 7

    def execute(self, model):
        pass

    def encode(self):
        return (
            (0b0000111 << 25)
            | (self.rs2 << 20)
            | (self.rs1 << 15)
            | (self.op << 12)
            | (self.rd << 7)
            | 0b0110011
        )


ops_alu = [
    SimpleOp(InstructionADDI, lambda rs1, imm: reg[rs1] + imm, "+i"),
    SimpleOp(InstructionADD, lambda rs1, rs2: reg[rs1] + reg[rs2], "+"),
    SimpleOp(InstructionSUB, lambda rs1, rs2: reg[rs1] - reg[rs2], "-"),
    SimpleOp(InstructionANDI, lambda rs1, imm: reg[rs1] & imm, "&i"),
    SimpleOp(InstructionAND, lambda rs1, rs2: reg[rs1] & reg[rs2], "&"),
    SimpleOp(InstructionORI, lambda rs1, imm: reg[rs1] | imm, "|i"),
    SimpleOp(InstructionOR, lambda rs1, rs2: reg[rs1] | reg[rs2], "|"),
    SimpleOp(InstructionXORI, lambda rs1, imm: reg[rs1] ^ imm, "^i"),
    SimpleOp(InstructionXOR, lambda rs1, rs2: reg[rs1] ^ reg[rs2], "^"),
    SimpleOp(InstructionSLTI, lambda rs1, imm: 1 if reg[rs1] < imm else 0, "<i"),
    SimpleOp(InstructionSLT, lambda rs1, rs2: 1 if reg[rs1] < reg[rs2] else 0, "<"),
    SimpleOp(
        InstructionSLTIU,
        lambda rs1, imm: 1 if (reg[rs1] & 0xFFFFFFFF) < (imm & 0xFFFFFFFF) else 0,
        "<iu",
    ),
    SimpleOp(
        InstructionSLTU,
        lambda rs1, rs2: 1 if (reg[rs1] & 0xFFFFFFFF) < (reg[rs2] & 0xFFFFFFFF) else 0,
        "<u",
    ),
    SimpleOp(InstructionSLLI, lambda rs1, imm: reg[rs1] << imm, "<<i"),
    SimpleOp(InstructionSLL, lambda rs1, rs2: reg[rs1] << (reg[rs2] & 0x1F), "<<"),
    SimpleOp(InstructionSRLI, lambda rs1, imm: (reg[rs1] & 0xFFFFFFFF) >> imm, ">>li"),
    SimpleOp(
        InstructionSRL,
        lambda rs1, rs2: (reg[rs1] & 0xFFFFFFFF) >> (reg[rs2] & 0x1F),
        ">>l",
    ),
    SimpleOp(InstructionSRAI, lambda rs1, imm: reg[rs1] >> imm, ">>i"),
    SimpleOp(InstructionSRA, lambda rs1, rs2: reg[rs1] >> (reg[rs2] & 0x1F), ">>"),
    SimpleOp(
        InstructionCZERO_EQZ, lambda rs1, rs2: 0 if reg[rs2] == 0 else reg[rs1], "?0"
    ),
    SimpleOp(
        InstructionCZERO_NEZ, lambda rs1, rs2: 0 if reg[rs2] != 0 else reg[rs1], "?!0"
    ),
    # Compressed instruction ops removed — hardware no longer supports RVC (Step 17.3)
]


@cocotb.test(skip=True)
async def test_random_alu(dut):
    dut._log.info("Start")

    clock = Clock(dut.clk, CLOCK_PERIOD_PS, unit="ps")
    cocotb.start_soon(clock.start())

    # Reset
    await reset(dut)

    # Should start reading flash after 1 cycle
    await ClockCycles(dut.clk, 1)
    await start_read(dut, 0)
    await boot_cpu(dut)

    seed = random.randint(0, 0xFFFFFFFF)
    # seed = 1508125843
    debug = False
    for test in range(2):
        random.seed(seed + test)
        dut._log.info("Running test with seed {}".format(seed + test))
        for i in range(1, 16):
            if i == 3:
                reg[i] = 0x1000400
            elif i == 4:
                reg[i] = 0x8000000
            else:
                reg[i] = random.randint(-0x80000000, 0x7FFFFFFF)
                if debug:
                    print("Set reg {} to {}".format(i, reg[i]))
                await load_reg(dut, i, reg[i])

        if False:
            for i in range(16):
                reg_value = (await read_reg(dut, i)).signed_integer
                if debug:
                    print("Reg {} is {}".format(i, reg_value))
                assert reg_value == reg[i]

        last_instr = ops_alu[0]
        for i in range(50):
            while True:
                try:
                    instr = random.choice(ops_alu)
                    instr.randomize()
                    rd = instr.get_valid_rd()
                    rs1 = instr.get_valid_rs1()
                    arg2 = instr.get_valid_arg2()

                    instr.execute_fn(rd, rs1, arg2)
                    break
                except ValueError:
                    pass

            if debug:
                print(
                    "x{} = x{} {} {}, now {} {:08x}".format(
                        rd, rs1, arg2, instr.name, reg[rd], instr.encode(rd, rs1, arg2)
                    )
                )
            await send_instr(dut, instr.encode(rd, rs1, arg2))
            # if debug:
            #    assert await read_reg(dut, rd) == reg[rd] & 0xFFFFFFFF

        for i in range(16):
            reg_value = await read_reg(dut, i)
            if debug:
                print("Reg x{} = {} should be {}".format(i, reg_value, reg[i]))
            assert reg_value & 0xFFFFFFFF == reg[i] & 0xFFFFFFFF


def encode_clw(reg, base_reg, imm):
    scrambled = (
        ((imm << (10 - 3)) & 0b1110000000000)
        | ((imm << (6 - 2)) & 0b0000001000000)
        | ((imm >> (6 - 5)) & 0b0000000100000)
    )
    return 0x4000 | scrambled | ((base_reg - 8) << 7) | ((reg - 8) << 2)


def encode_lh(reg, base_reg, imm):
    scrambled = (imm << (5 - 1)) & 0b100000
    return 0x8440 | scrambled | ((base_reg - 8) << 7) | ((reg - 8) << 2)


def encode_lhu(reg, base_reg, imm):
    scrambled = (imm << (5 - 1)) & 0b100000
    return 0x8400 | scrambled | ((base_reg - 8) << 7) | ((reg - 8) << 2)


def encode_lbu(reg, base_reg, imm):
    scrambled = ((imm << (5 - 1)) & 0b0100000) | ((imm << (6 - 0)) & 0b1000000)
    return 0x8000 | scrambled | ((base_reg - 8) << 7) | ((reg - 8) << 2)


async def set_reg(dut, rd, value):
    await send_instr(dut, InstructionLUI(rd, (value + 0x800) >> 12).encode())
    await send_instr(
        dut, InstructionADDI(rd, rd, ((value + 0x800) & 0xFFF) - 0x800).encode()
    )
    reg[rd] = value


class CLoadOp:
    def __init__(self, encoder, min_imm, max_imm, imm_mul, bytes, fn, name):
        self.encoder = encoder
        self.fn = fn
        self.name = name
        self.is_mem_op = True
        self.min_imm = min_imm
        self.max_imm = max_imm
        self.imm_mul = imm_mul
        self.bytes = bytes

    def randomize(self):
        self.rd = random.randint(8, 15)
        self.base_reg = random.randint(8, 15)
        self.imm = random.randint(self.min_imm, self.max_imm) * self.imm_mul
        self.val = random.randint(-0x80000000, 0x7FFFFFFF)

    def execute_fn(self, rd, rs1, arg2):
        if rd != 0 and rd != 3 and rd != 4:
            reg[rd] = self.fn(self.val)

    def encode(self, rd, rs1, arg2):
        return self.encoder(rd, rs1, arg2)

    def get_valid_rd(self):
        return self.rd

    def get_valid_rs1(self):
        return self.base_reg

    def get_valid_arg2(self):
        return self.imm

    async def do_mem_op(self, dut, addr):
        # print("Load {} from addr {:08x}".format(self.val, addr))
        await expect_load(dut, addr, self.val, abs(self.bytes))


class LoadOp:
    def __init__(self, instr, min_imm, max_imm, imm_mul, bytes, fn, name):
        self.instr = instr
        self.fn = fn
        self.name = name
        self.is_mem_op = True
        self.min_imm = min_imm
        self.max_imm = max_imm
        self.imm_mul = imm_mul
        self.bytes = bytes

    def randomize(self):
        self.rd = random.randint(0, 15)
        while True:
            self.base_reg = random.randint(1, 15)
            if self.base_reg not in (gp, tp):
                break
        self.imm = random.randint(self.min_imm, self.max_imm) * self.imm_mul
        self.val = random.randint(-0x80000000, 0x7FFFFFFF)

    def execute_fn(self, rd, rs1, arg2):
        if rd != 0 and rd != 3 and rd != 4:
            reg[rd] = self.fn(self.val)

    def encode(self, rd, rs1, arg2):
        return self.instr(rd, rs1, arg2).encode()

    def get_valid_rd(self):
        return self.rd

    def get_valid_rs1(self):
        return self.base_reg

    def get_valid_arg2(self):
        return self.imm

    async def do_mem_op(self, dut, addr):
        # print("Load {} from addr {:08x}".format(self.val, addr))
        await expect_load(dut, addr, self.val, abs(self.bytes))


def encode_csw(base_reg, reg, imm):
    scrambled = (
        ((imm << (10 - 3)) & 0b1110000000000)
        | ((imm << (6 - 2)) & 0b0000001000000)
        | ((imm >> (6 - 5)) & 0b0000000100000)
    )
    return 0xC000 | scrambled | ((base_reg - 8) << 7) | ((reg - 8) << 2)


class CStoreOp:
    def __init__(self, encoder, min_imm, max_imm, imm_mul, bytes, fn, name):
        self.encoder = encoder
        self.fn = fn
        self.name = name
        self.is_mem_op = True
        self.min_imm = min_imm
        self.max_imm = max_imm
        self.imm_mul = imm_mul
        self.bytes = bytes

    def randomize(self):
        self.rs1 = random.randint(8, 15)
        while True:
            self.base_reg = random.randint(8, 15)
            if self.base_reg != self.rs1:
                break
        self.imm = random.randint(self.min_imm, self.max_imm) * self.imm_mul

    def execute_fn(self, rd, rs1, arg2):
        pass

    def encode(self, rd, rs1, arg2):
        return self.encoder(self.base_reg, self.rs1, arg2)

    def get_valid_rd(self):
        return self.base_reg

    def get_valid_rs1(self):
        return self.rs1

    def get_valid_arg2(self):
        return self.imm

    async def do_mem_op(self, dut, addr):
        # print("Load {} from addr {:08x}".format(self.val, addr))
        assert await expect_store(dut, addr, self.bytes) == self.fn(self.rs1)


class StoreOp:
    def __init__(self, instr, min_imm, max_imm, imm_mul, bytes, fn, name):
        self.instr = instr
        self.fn = fn
        self.name = name
        self.is_mem_op = True
        self.min_imm = min_imm
        self.max_imm = max_imm
        self.imm_mul = imm_mul
        self.bytes = bytes

    def randomize(self):
        self.rs1 = random.randint(0, 15)
        while True:
            self.base_reg = random.randint(1, 15)
            if self.base_reg not in (self.rs1, gp, tp):
                break
        self.imm = random.randint(self.min_imm, self.max_imm) * self.imm_mul

    def execute_fn(self, rd, rs1, arg2):
        pass

    def encode(self, rd, rs1, arg2):
        return self.instr(self.base_reg, self.rs1, arg2).encode()

    def get_valid_rd(self):
        return self.base_reg

    def get_valid_rs1(self):
        return self.rs1

    def get_valid_arg2(self):
        return self.imm

    async def do_mem_op(self, dut, addr):
        # print("Load {} from addr {:08x}".format(self.val, addr))
        assert await expect_store(dut, addr, self.bytes) == self.fn(self.rs1)


ops = [
    SimpleOp(InstructionADDI, lambda rs1, imm: reg[rs1] + imm, "+i"),
    SimpleOp(InstructionADD, lambda rs1, rs2: reg[rs1] + reg[rs2], "+"),
    SimpleOp(InstructionSUB, lambda rs1, rs2: reg[rs1] - reg[rs2], "-"),
    SimpleOp(InstructionANDI, lambda rs1, imm: reg[rs1] & imm, "&i"),
    SimpleOp(InstructionAND, lambda rs1, rs2: reg[rs1] & reg[rs2], "&"),
    SimpleOp(InstructionORI, lambda rs1, imm: reg[rs1] | imm, "|i"),
    SimpleOp(InstructionOR, lambda rs1, rs2: reg[rs1] | reg[rs2], "|"),
    SimpleOp(InstructionXORI, lambda rs1, imm: reg[rs1] ^ imm, "^i"),
    SimpleOp(InstructionXOR, lambda rs1, rs2: reg[rs1] ^ reg[rs2], "^"),
    SimpleOp(InstructionSLTI, lambda rs1, imm: 1 if reg[rs1] < imm else 0, "<i"),
    SimpleOp(InstructionSLT, lambda rs1, rs2: 1 if reg[rs1] < reg[rs2] else 0, "<"),
    SimpleOp(
        InstructionSLTIU,
        lambda rs1, imm: 1 if (reg[rs1] & 0xFFFFFFFF) < (imm & 0xFFFFFFFF) else 0,
        "<iu",
    ),
    SimpleOp(
        InstructionSLTU,
        lambda rs1, rs2: 1 if (reg[rs1] & 0xFFFFFFFF) < (reg[rs2] & 0xFFFFFFFF) else 0,
        "<u",
    ),
    SimpleOp(InstructionSLLI, lambda rs1, imm: reg[rs1] << imm, "<<i"),
    SimpleOp(InstructionSLL, lambda rs1, rs2: reg[rs1] << (reg[rs2] & 0x1F), "<<"),
    SimpleOp(InstructionSRLI, lambda rs1, imm: (reg[rs1] & 0xFFFFFFFF) >> imm, ">>li"),
    SimpleOp(
        InstructionSRL,
        lambda rs1, rs2: (reg[rs1] & 0xFFFFFFFF) >> (reg[rs2] & 0x1F),
        ">>l",
    ),
    SimpleOp(InstructionSRAI, lambda rs1, imm: reg[rs1] >> imm, ">>i"),
    SimpleOp(InstructionSRA, lambda rs1, rs2: reg[rs1] >> (reg[rs2] & 0x1F), ">>"),
    SimpleOp(
        InstructionCZERO_EQZ, lambda rs1, rs2: 0 if reg[rs2] == 0 else reg[rs1], "?0"
    ),
    SimpleOp(
        InstructionCZERO_NEZ, lambda rs1, rs2: 0 if reg[rs2] != 0 else reg[rs1], "?!0"
    ),
    # Compressed instruction ops removed — hardware no longer supports RVC (Step 17.3)
    LoadOp(InstructionLW, -0x800, 0x7FF, 1, 4, lambda val: val, "lw"),
    LoadOp(
        InstructionLH,
        -0x800,
        0x7FF,
        1,
        -2,
        lambda val: (val & 0xFFFF) - 0x10000 if (val & 0x8000) != 0 else val & 0xFFFF,
        "lh",
    ),
    LoadOp(
        InstructionLB,
        -0x800,
        0x7FF,
        1,
        -1,
        lambda val: (val & 0xFF) - 0x100 if (val & 0x80) != 0 else val & 0xFF,
        "lb",
    ),
    LoadOp(InstructionLHU, -0x800, 0x7FF, 1, 2, lambda val: val & 0xFFFF, "lhu"),
    LoadOp(InstructionLBU, -0x800, 0x7FF, 1, 1, lambda val: val & 0xFF, "lbu"),
    # CStoreOp(encode_csw) removed — hardware no longer supports RVC (Step 17.3)
    StoreOp(
        InstructionSW, -0x800, 0x7FF, 1, 4, lambda rs1: reg[rs1] & 0xFFFFFFFF, "sw"
    ),
    StoreOp(InstructionSH, -0x800, 0x7FF, 1, 2, lambda rs1: reg[rs1] & 0xFFFF, "sh"),
    StoreOp(InstructionSB, -0x800, 0x7FF, 1, 1, lambda rs1: reg[rs1] & 0xFF, "sb"),
]


@cocotb.test(skip=True)
async def test_random(dut):
    dut._log.info("Start")

    clock = Clock(dut.clk, CLOCK_PERIOD_PS, unit="ps")
    cocotb.start_soon(clock.start())

    # Reset
    await reset(dut)

    # Should start reading flash after 1 cycle
    await ClockCycles(dut.clk, 1)
    await start_read(dut, 0)
    await boot_cpu(dut)

    seed = random.randint(0, 0xFFFFFFFF)

    for test in range(1):
        random.seed(seed + test)
        dut._log.info("Running test with seed {}".format(seed + test))
        for i in range(1, 16):
            if i == 3:
                reg[i] = 0x1000400
            elif i == 4:
                reg[i] = 0x8000000
            else:
                reg[i] = random.randint(-0x80000000, 0x7FFFFFFF)
                await load_reg(dut, i, reg[i])


        last_instr = ops[0]
        for i in range(50):
            while True:
                try:
                    instr = random.choice(ops)
                    instr.randomize()
                    rd = instr.get_valid_rd()
                    rs1 = instr.get_valid_rs1()
                    arg2 = instr.get_valid_arg2()

                    if instr.is_mem_op:
                        addr = random.randint(
                            0x1000000 - instr.imm, 0x1FFFFFC - instr.imm
                        )
                        await set_reg(dut, instr.base_reg, addr)

                    instr.execute_fn(rd, rs1, arg2)
                    break
                except ValueError:
                    pass

            await send_instr(dut, instr.encode(rd, rs1, arg2))
            if instr.is_mem_op:
                if addr < 0x4000000:
                    assert addr + instr.imm >= 0
                    assert addr + instr.imm < 0x2000000
                    await instr.do_mem_op(dut, addr + instr.imm)
            # if True:
            #    assert await read_reg(dut, rd) == reg[rd] & 0xFFFFFFFF

        for i in range(16):
            reg_value = await read_reg(dut, i)
            assert reg_value & 0xFFFFFFFF == reg[i] & 0xFFFFFFFF
