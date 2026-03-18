# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

BOLD := \033[1m
RESET   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  triangle:\t\tRender a single triangle frame.$(RESET)"
	@echo -e "  burn:\t\t\tGenerate bitstream and flash to FPGA."
	@echo -e "  tio:\t\t\tConnect to the serial console."
	@echo -e "  reset:\t\tReset the CPU."
	@echo -e "  borg.json:\t\tRun synthesis."
	@echo -e "  borg.asc:\t\tRun Place & Route (pnr)."
	@echo -e "  borg.bin:\t\tGenerate the FPGA bitstream."
	@echo -e "  resource_check:\tCheck LUT and DFF usage coefficients."
	@echo -e "  test-all:\t\tRun all FPGA-side tests."
	@echo -e "  clean:\t\tRemove build artifacts."


######### Build hardware ###########################################################################

SRC_FILES = $(wildcard ../out/borg/verilog/*.sv) \
	    $(wildcard ../out/tinyqv/verilog/*.sv) \

CHISEL_SRCS = $(wildcard ../borg/src/*.scala) $(wildcard ../tinyqv/src/*.scala)

# Regenerate Verilog from Chisel when sources change
../out/borg/verilog/Borg.sv: $(CHISEL_SRCS)
	cd .. && mill borg.runMain borg.Main && mill tinyqv.runMain tinyqv.Main

# Hardware limits
MAX_LUTS = 5280
MAX_DFFS = 5280

YOSYS_SCRIPT = read -sv $(SRC_FILES); synth_ice40 -dsp -abc9 -device u -top tinyQV_top \
	       -json borg.json
# Build netlist
borg.json: $(SRC_FILES) ../out/borg/verilog/Borg.sv
	yosys -p "$(YOSYS_SCRIPT)" -DICE40 -DPURE_RTL -DSYNTH_FPGA > yosys.log

# Place and Route (P&R) — freq derived from MmioMap.CLOCK_MHZ via generated header
CLOCK_FREQ := $(shell grep -m1 'define CLOCK_MHZ' firmware/borg_mmio.h | awk '{print $$3}')
borg.asc: borg.json
	nextpnr-ice40 --up5k --package sg48 --json borg.json --pcf ../out/borg/verilog/pico_ice.pcf --asc borg.asc \
		--freq $(CLOCK_FREQ)

# Generate FPGA bitstream
borg.bin: borg.asc
	icepack $< $@

# Burn to FPGA
burn: borg.bin
	mpremote mount . + run host/program_bitstream.py

# Print used FPGA resources (after borg.json has been generated)
resource_check: yosys.log
	@grep "SB_LUT4" yosys.log | tail -n1 | awk -v max=$(MAX_LUTS) '{ \
	        percent=($$1/max)*100; \
	        printf "SB_LUT4 (Logic): \t%d \t%.0f%%\n", $$1, percent \
	}'
	@grep "SB_DFF" yosys.log | awk -v max=$(MAX_DFFS) '{sum+=$$$$2;} END { \
	        percent=(sum/max)*100; \
	        printf "SB_DFF* (Registers): \t%d \t%.0f%%\n", sum, percent \
	}'

######### Build software and run ###################################################################

# Build firmware and run triangle rendering
triangle:
	$(MAKE) -C firmware triangle
	mpremote mount . + exec "import sys; sys.path.append('host'); import triangle; triangle.run_single_frame(0)" | tee /tmp/mpremote_output.txt

# Catch debug output (preferably in a second shell)
tio:
	tio /dev/ttyACM1 | tee /tmp/tio_output.txt

# Reset FPGA
reset:
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_tinyqv; run_tinyqv.run(query=False, stop=False)"

clean:
	rm -f borg.bin borg.json borg.asc yosys.log nextpnr.log triangle*.ppm
	$(MAKE) -C firmware clean
	$(MAKE) -C sdk clean

test-all:
	$(MAKE) -C firmware/compiler test

.PHONY: all help burn clean reset resource_check test-all triangle tio
