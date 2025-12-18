import cocotb
from cocotb.clock import Clock
from cocotb.triggers import Timer
from tqv import TinyQV

PERIPHERAL_NUM = 39 # Or whichever index you used in peripherals.v

@cocotb.test()
async def test_borg_simple(dut):
    dut._log.info("Starting Borg Simple Test")

    # 1. Start Clock (Matching the working example's 10MHz/100ns)
    clock = Clock(dut.clk, 100, unit="ns")
    cocotb.start_soon(clock.start())

    # 2. Initialize TinyQV helper
    tqv = TinyQV(dut, PERIPHERAL_NUM)

    # 3. Reset
    await tqv.reset()

    # 4. Test Borg Logic: Write to ADDR 0, expect (val + 1)
    test_val = 0x42
    dut._log.info(f"Writing {hex(test_val)} to Borg...")
    await tqv.write_word_reg(0, test_val)
    
    dut._log.info("Reading back result...")
    # Because our Chisel code does: result = RegNext(reg_value + 1)
    # The tqv.read_word_reg helper will wait for data_ready automatically.
    actual_val = await tqv.read_word_reg(0)
    
    expected_val = test_val + 1
    dut._log.info(f"Read back: {hex(actual_val)}")

    assert actual_val == expected_val, f"Borg failed! Expected {hex(expected_val)}, got {hex(actual_val)}"
