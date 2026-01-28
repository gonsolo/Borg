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

all:
	yosys -p "read -sv $(FILES) ; synth_ice40 -abc9 -device u -top tinyQV_top -json borg.json" -DICE40 -DPURE_RTL -DSYNTH_FPGA  > yosys.log

