BORG_DIR = ../src/user_peripherals/borg
TINYQV_DIR = ../src/tinyQV/cpu

FILES = pico_ice.v \
    ../src/peripherals.v \
    ../src/peri_*.v \
    ../src/user_peripherals/spi.v \
    $(BORG_DIR)/*.sv \
    $(TINYQV_DIR)/*.v \
    ../src/tinyQV/peri/uart/uart_tx.v \
    ../src/user_peripherals/uart/*.v

borg.json:
	yosys -p "read -sv $(FILES) ; synth_ice40 -abc9 -device u -top tinyQV_top -json borg.json" -DICE40 -DPURE_RTL -DSYNTH_FPGA  > yosys.log

# Hardware limits for the Lattice iCE40UP5K (pico-ice)
MAX_LUTS = 5280
MAX_DFFS = 5280

resource_check: yosys.log
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

clean:
	rm -f borg.json yosys.log
.PHONY: clean resource_check
