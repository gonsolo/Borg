import random

import cocotb
from cocotb.triggers import ClockCycles, Timer

from riscvmodel.insn import *

from riscvmodel.regnames import x0, gp, tp, a0


async def reset(dut, latency=1, ui_in=0x80):
  # Reset
  dut._log.info(f"Reset, latency {latency}")
  dut.ena.value = 1
  dut.ui_in_base.value = ui_in
  dut.uio_in.value = 0
  dut.qspi_data_in.value = 0
  dut.rst_n.value = 1
  dut.uart_rx.value = 1
  await ClockCycles(dut.clk, 2)
  dut.rst_n.value = 0
  dut.latency_cfg.value = latency
  await ClockCycles(dut.clk, 1)
  assert dut.uio_oe.value == 0
  await ClockCycles(dut.clk, 9)
  dut.rst_n.value = 1
  await ClockCycles(dut.clk, 1)
  assert dut.uio_oe.value == 0b11001001

select = None

async def start_read(dut, addr):
    global select

    if addr is None:
        select = dut.qspi_flash_select
    elif addr >= 0x1800000:
        select = dut.qspi_ram_b_select
    elif addr >= 0x1000000:
        select = dut.qspi_ram_a_select
    else:
        select = dut.qspi_flash_select
    
    assert select.value == 0
    assert dut.qspi_flash_select.value == (0 if dut.qspi_flash_select == select else 1)
    assert dut.qspi_ram_a_select.value == (0 if dut.qspi_ram_a_select == select else 1)
    assert dut.qspi_ram_b_select.value == (0 if dut.qspi_ram_b_select == select else 1)
    assert dut.qspi_clk_out.value == 0

    if dut.qspi_flash_select != select:
        # Command
        cmd = 0x0B
        assert dut.qspi_data_oe.value == 0xF    # Command
        for i in range(2):
            await ClockCycles(dut.clk, 1, False)
            assert select.value == 0
            assert dut.qspi_clk_out.value == 1
            assert dut.qspi_data_out.value == (cmd & 0xF0) >> 4
            assert dut.qspi_data_oe.value == 0xF
            cmd <<= 4
            await ClockCycles(dut.clk, 1, False)
            assert select.value == 0
            assert dut.qspi_clk_out.value == 0

    # Address
    assert dut.qspi_data_oe.value == 0xF
    for i in range(6):
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 1
        if addr is not None:
            assert dut.qspi_data_out.value == (addr >> (20 - i * 4)) & 0xF
        assert dut.qspi_data_oe.value == 0xF
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 0

    # Dummy
    if dut.qspi_flash_select == select:
        for i in range(2):
            await ClockCycles(dut.clk, 1, False)
            assert select.value == 0
            assert dut.qspi_clk_out.value == 1
            assert dut.qspi_data_oe.value == 0xF
            assert dut.qspi_data_out.value == 0xA
            await ClockCycles(dut.clk, 1, False)
            assert select.value == 0
            assert dut.qspi_clk_out.value == 0

    for i in range(4):
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 1
        assert dut.qspi_data_oe.value == 0
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 0


async def start_write(dut, addr):
    global select

    if addr >= 0x1800000:
        select = dut.qspi_ram_b_select
    else:
        select = dut.qspi_ram_a_select

    assert select.value == 0
    assert dut.qspi_flash_select.value == 1
    assert dut.qspi_ram_a_select.value == (0 if dut.qspi_ram_a_select == select else 1)
    assert dut.qspi_ram_b_select.value == (0 if dut.qspi_ram_b_select == select else 1)
    assert dut.qspi_clk_out.value == 0
    assert dut.qspi_data_oe.value == 0xF

    # Command
    cmd = 0x02
    for i in range(2):
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 1
        assert dut.qspi_data_out.value == (cmd & 0xF0) >> 4
        assert dut.qspi_data_oe.value == 0xF
        cmd <<= 4
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 0

    # Address
    for i in range(6):
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 1
        assert dut.qspi_data_out.value == (addr >> (20 - i * 4)) & 0xF
        assert dut.qspi_data_oe.value == 0xF
        await ClockCycles(dut.clk, 1, False)
        assert select.value == 0
        assert dut.qspi_clk_out.value == 0


nibble_shift_order = [4, 0, 12, 8, 20, 16, 28, 24]

async def send_instr(dut, data, ok_to_exit=False, allow_long_delay=False):
    instr_len = 8 if (data & 3) == 3 else 4
    for i in range(instr_len):
        dut.qspi_data_in.value = (data >> (nibble_shift_order[i])) & 0xF
        await ClockCycles(dut.clk, 1, False)
        for _ in range(400 if allow_long_delay else 20):
            if ok_to_exit and dut.qspi_flash_select.value == 1:
                return
            assert dut.qspi_flash_select.value == 0
            if dut.qspi_clk_out.value == 0:
                await ClockCycles(dut.clk, 1, False)
            else:
                break
        assert dut.qspi_clk_out.value == 1
        assert dut.qspi_data_oe.value == 0
        await ClockCycles(dut.clk, 1, False)
        assert dut.qspi_clk_out.value == 0
        if i != instr_len - 1:
            if ok_to_exit and dut.qspi_flash_select.value == 1:
                return
            assert dut.qspi_flash_select.value == 0

async def expect_load(dut, addr, val, bytes=4):
    if addr >= 0x1800000:
        select = dut.qspi_ram_b_select
    elif addr >= 0x1000000:
        select = dut.qspi_ram_a_select
    else:
        assert False # Load from flash not currently supported in this test

    for i in range(12):
        if select.value == 0:
            await start_read(dut, addr)
            dut.qspi_data_in.value = (val >> (nibble_shift_order[0])) & 0xF
            for j in range(1,bytes*2):
                await ClockCycles(dut.clk, 1, False)
                assert select.value == 0
                assert dut.qspi_clk_out.value == 1
                assert dut.qspi_data_oe.value == 0
                await ClockCycles(dut.clk, 1, False)
                assert dut.qspi_clk_out.value == 0
                dut.qspi_data_in.value = (val >> (nibble_shift_order[j])) & 0xF
            break
        elif dut.qspi_flash_select.value == 0:
            await send_instr(dut, 0x0001, True)
        else:
            await ClockCycles(dut.clk, 1, False)
    else:
        assert False

    for i in range(8):
        await ClockCycles(dut.clk, 1)
        if dut.qspi_flash_select.value == 0:
            if hasattr(dut.user_project, "i_tinyqv"):
                await start_read(dut, dut.user_project.i_tinyqv.instr_addr.value.to_unsigned() * 2)
            else:
                await start_read(dut, None)
            break
    else:
        assert False

async def load_reg(dut, reg, value):
    offset = random.randint(-0x400, 0x3FF)
    instr = InstructionLW(reg, gp, offset).encode()
    await send_instr(dut, instr)

    await expect_load(dut, 0x1000400 + offset, value)


send_nops = True
nop_task = None

async def nops_loop(dut):
    while send_nops:
        await send_instr(dut, InstructionADDI(x0, x0, 0).encode())

async def start_nops(dut):
    global send_nops, nop_task
    send_nops = True
    nop_task = cocotb.start_soon(nops_loop(dut))

    # This ensures that the nop task is actually started, so that it can be instantly stopped.
    await Timer(2, "ps")

async def stop_nops():
    global send_nops, nop_task
    send_nops = False
    if nop_task is not None:
        await nop_task
    nop_task = None

async def read_byte(dut, reg, expected_val):
  await send_instr(dut, InstructionSW(tp, reg, 0x18).encode())

  await start_nops(dut)
  for i in range(80):
      if dut.debug_uart_tx.value == 0:
          break
      else:
          await Timer(5, "ns")
  assert dut.debug_uart_tx.value == 0
  bit_time = 250
  await Timer(bit_time / 2, "ns")
  assert dut.debug_uart_tx.value == 0
  for i in range(8):
      await Timer(bit_time, "ns")
      assert dut.debug_uart_tx.value == (expected_val & 1)
      expected_val >>= 1
  await Timer(bit_time, "ns")
  assert dut.debug_uart_tx.value == 1

  await stop_nops()

async def expect_store(dut, addr, bytes=4, allow_long_delay=False):
    if addr >= 0x1800000:
        select = dut.qspi_ram_b_select
    elif addr >= 0x1000000:
        select = dut.qspi_ram_a_select
    else:
        assert False

    val = 0
    for i in range(12):
        if select.value == 0:
            await start_write(dut, addr)
            for j in range(bytes*2):
                await ClockCycles(dut.clk, 1, False)
                assert select.value == 0
                if j > 0 and (j % 8) == 0:
                    await ClockCycles(dut.clk, 1, False)
                    assert select.value == 0
                    assert dut.qspi_clk_out.value == 0
                    await ClockCycles(dut.clk, 1, False)
                assert dut.qspi_clk_out.value == 1
                assert dut.qspi_data_oe.value == 0xF
                val |= dut.qspi_data_out.value.to_unsigned() << (nibble_shift_order[j % 8])
                await ClockCycles(dut.clk, 1, False)
                assert select.value == (1 if j == bytes*2-1 else 0)
                assert dut.qspi_clk_out.value == 0
            await ClockCycles(dut.clk, 1, False)
            assert select.value == 1
            break
        elif dut.qspi_flash_select.value == 0:
            await send_instr(dut, 0x0001, True, allow_long_delay)
        else:
            await ClockCycles(dut.clk, 1, False)
    else:
        assert False

    for i in range(8):
        await ClockCycles(dut.clk, 1)
        if dut.qspi_flash_select.value == 0:
            if hasattr(dut.user_project, "i_tinyqv"):
                await start_read(dut, dut.user_project.i_tinyqv.instr_addr.value.to_unsigned() * 2)
            else:
                await start_read(dut, None)
            break
    else:
        assert False

    return val

async def read_reg(dut, reg, allow_long_delay=False):
    offset = random.randint(-0x400, 0x3FF)
    instr = InstructionSW(gp, reg, offset).encode()
    await send_instr(dut, instr)

    return await expect_store(dut, 0x1000400 + offset, 4, allow_long_delay)

async def set_all_outputs_to_peripheral(dut, peripheral_num):
    await send_instr(dut, InstructionADDI(a0, x0, 0xc0).encode())
    await send_instr(dut, InstructionSW(tp, a0, 0xc).encode())
    await send_instr(dut, InstructionADDI(a0, x0, peripheral_num).encode())
    for func_sel in range(0x60, 0x80, 4):
        await send_instr(dut, InstructionSW(tp, a0, func_sel).encode())
