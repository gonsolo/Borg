BORG_DIR = ../src/user_peripherals/borg
TINYQV_DIR = ../src/tinyQV/cpu

# Use $(wildcard) so Make can track individual file changes
SRC_FILES = pico_ice.v \
            ../src/peripherals.v \
            $(wildcard ../src/peri_*.v) \
            ../src/user_peripherals/spi.v \
            $(wildcard $(BORG_DIR)/*.sv) \
            $(wildcard $(TINYQV_DIR)/*.v) \
            ../src/tinyQV/peri/uart/uart_tx.v \
            $(wildcard ../src/user_peripherals/uart/*.v)

# Hardware limits
MAX_LUTS = 5280
MAX_DFFS = 5280

# To see something you have to connect to the debug probe in second terminal:
# tio /dev/ttyACM1
# And you have to burn first, of course
run:
	#mpremote mount micropython + exec "import run_tinyqv ; run_tinyqv.execute('hello.bin')"
	mpremote mount micropython + exec "import run_tinyqv ; run_tinyqv.execute('gonzo.bin')"

reset:
	mpremote mount micropython + exec "import run_tinyqv ; run_tinyqv.run(query=False, stop=False)"

burn: borg.bin
	mpremote mount . + run micropython/fpga_flash_prog.py

borg.bin: borg.asc
	icepack $< $@

borg.asc: borg.json
	@echo "--- Running in Fast Mode ---"
	nextpnr-ice40 --up5k --package sg48 --json borg.json --pcf pico_ice.pcf --asc borg.asc --opt-timing --freq 4

borg.json: $(SRC_FILES)
	yosys -p "read -sv $(SRC_FILES) ; synth_ice40 -dsp -abc9 -device u -top tinyQV_top -json borg.json" \
	-DICE40 -DPURE_RTL -DSYNTH_FPGA > yosys.log

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
	rm -f borg.bin borg.json borg.asc yosys.log nextpnr.log

.PHONY: clean resource_check run

