FILES = pico_ice.v \
	../src/peripherals.v \
	../src/peri_full_example.v \
	../src/peri_full_empty.v \
	../src/peri_full_example_no_irq.v \
	../src/peri_byte_example.v \
  	../src/user_peripherals/spi.v \
	../src/user_peripherals/borg/AddRawFN.sv \
	../src/user_peripherals/borg/AddRecFN.sv \
	../src/user_peripherals/borg/MulFullRawFN.sv \
	../src/user_peripherals/borg/MulRawFN.sv \
	../src/user_peripherals/borg/MulRecFN.sv \
	../src/user_peripherals/borg/Borg.sv \
	../src/user_peripherals/borg/RoundAnyRawFNToRecFN_ie8_is26_oe8_os24.sv \
	../src/user_peripherals/borg/RoundRawFNToRecFN_e8_s24.sv \
	../src/tinyQV/cpu/tinyqv.v \
	../src/tinyQV/cpu/alu.v \
	../src/tinyQV/cpu/core.v \
	../src/tinyQV/cpu/counter.v \
	../src/tinyQV/cpu/cpu.v \
	../src/tinyQV/cpu/decode.v \
	../src/tinyQV/cpu/mem_ctrl.v \
	../src/tinyQV/cpu/qspi_ctrl.v \
	../src/tinyQV/cpu/register.v \
	../src/tinyQV/cpu/latch_reg.v \
	../src/tinyQV/cpu/time.v \
	../src/tinyQV/peri/uart/uart_tx.v \
	../src/user_peripherals/uart/peri_uart.v \
	../src/user_peripherals/uart/uart_rx.v \
	../src/user_peripherals/uart/uart_tx.v

borg.json:
	yosys -p "read -sv $(FILES) ; synth_ice40 -abc9 -device u -top tinyQV_top -json borg.json" -DICE40 -DPURE_RTL -DSYNTH_FPGA  > yosys.log

# Hardware limits for the Lattice iCE40UP5K (pico-ice)
MAX_LUTS = 5280
MAX_DFFS = 5280

resource_check:
	@# Process LUTs: $1 is the count in the yosys log
	@grep "SB_LUT4" yosys.log | tail -n1 | awk -v max=$(MAX_LUTS) '{ \
		percent=($$1/max)*100; \
		printf "SB_LUT4 (Logic): \t%d \t%.0f%%\n", $$1, percent \
	}'
	@# Process DFFs: $$$$2 is needed to pass literal $2 to awk for the count column
	@grep "SB_DFF" yosys.log | awk -v max=$(MAX_DFFS) '{sum+=$$$$2;} END { \
		percent=(sum/max)*100; \
		printf "SB_DFF* (Registers): \t%d \t%.0f%%\n", sum, percent \
	}'

.PHONY: resource_check

.PHONY: resource_check
