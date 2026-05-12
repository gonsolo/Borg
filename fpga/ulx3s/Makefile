# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

# ULX3S (Lattice ECP5-85K) build — openFPGALoader programming flow.
# Board connects via US1 (FT231X) on /dev/ttyUSB0.

.NOTPARALLEL:

BOLD  := \033[1m
RESET := \033[0m

all: help
help:
	@echo "commands:"
	@echo -e "$(BOLD)  load:\t\t\tSynthesize, P&R and load bitstream to FPGA SRAM.$(RESET)"
	@echo -e "$(BOLD)  flash:\t\t\tSynthesize, P&R and write bitstream to config FLASH.$(RESET)"
	@echo -e "  borg.bit:\t\tGenerate the FPGA bitstream."
	@echo -e "  borg.json:\t\tRun synthesis (Yosys)."
	@echo -e "  borg.config:\t\tRun Place & Route (nextpnr-ecp5)."
	@echo -e "  print_resources:\tCheck LUT, DFF, BRAM usage."
	@echo -e "  tio:\t\t\tConnect to the serial console (/dev/ttyUSB0)."
	@echo -e "  install-udev:\t\tInstall udev rules (run once, needs sudo)."
	@echo -e "  generate_verilog_ulx3s: Regenerate Verilog from Chisel."
	@echo -e "  clean:\t\tRemove build artifacts."

######### Build hardware ###########################################################################

ULX3S_VERILOG_DIR = ../../out/ulx3s/verilog

# All ECP5 SystemVerilog emitted by ULX3SMain into out/ulx3s/verilog/
SRC_FILES = $$(find $(ULX3S_VERILOG_DIR) -maxdepth 1 -name '*.sv' | tr '\n' ' ')

CHISEL_SRCS = $(wildcard ../../hardware/borg/src/*.scala) \
              $(wildcard ../../hardware/soc/src/*.scala) \
              $(wildcard ../../hardware/tinyqv/src/*.scala) \
              $(wildcard tinyqv/src/*.scala)

# Stamp file — regenerates Verilog only when Chisel sources change.
# generate_verilog_ulx3s is phony (always invokes mill) so without a stamp
# every make invocation would retrigger Yosys unnecessarily.
.verilog_stamp_ulx3s: $(CHISEL_SRCS)
	$(MAKE) -C ../.. generate_verilog_ulx3s
	@touch $@

# LPF emitted alongside the Verilog by ULX3SMain
LPF = $(ULX3S_VERILOG_DIR)/ulx3s.lpf

# ECP5-85K resource limits
MAX_LUT4  = 83640
MAX_DFF   = 83640

# Synthesis — ECP5 target, DSP and ABC9 enabled
borg.json: .verilog_stamp_ulx3s
	@echo -e "$(BOLD)[SYNTH]$(RESET) Running Yosys for ECP5 ..."
	yosys -p "read -sv $(SRC_FILES); \
	           read_verilog -sv -lib $(CURDIR)/primitives/ecp5_prims.v; \
	           synth_ecp5 -abc9 -top ulx3s_top -json borg.json; \
	           stat -top ulx3s_top" \
	      -DECP5 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ \
	      > yosys.log
	@echo -e "$(BOLD)[SYNTH]$(RESET) Done."

# Clock frequency from ULX3SMain default (25 MHz oscillator)
CLOCK_FREQ = 25

# Place and Route — ECP5-85F, CABGA381 package
borg.config: borg.json
	@echo -e "$(BOLD)[PNR]$(RESET) Running nextpnr-ecp5 ..."
	nextpnr-ecp5 \
	  --85k --package CABGA381 \
	  --json borg.json \
	  --lpf $(LPF) \
	  --textcfg borg.config \
	  --freq $(CLOCK_FREQ) \
	  --timing-allow-fail \
	  --log nextpnr.log \
	  --seed 0
	@echo -e "$(BOLD)[PNR]$(RESET) Done."

# Generate bitstream
borg.bit: borg.config
	@echo -e "$(BOLD)[PACK]$(RESET) Packing bitstream ..."
	ecppack --svf borg.svf borg.config borg.bit
	@echo -e "$(BOLD)[PACK]$(RESET) Done — borg.bit ready."

# Load to FPGA SRAM (volatile, lost on power cycle)
load: borg.bit
	@echo -e "$(BOLD)[LOAD]$(RESET) Programming FPGA SRAM via openFPGALoader ..."
	openFPGALoader -b ulx3s borg.bit
	@echo -e "$(BOLD)[LOAD]$(RESET) Done."

# Write to config FLASH (persistent)
flash: borg.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing bitstream to config FLASH via openFPGALoader ..."
	openFPGALoader -b ulx3s -f borg.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Done."

######### Firmware (QSPI PMOD flash) #############################################################

FIRMWARE_DIR = ../../software/borg

# Build + flash firmware to the PMOD flash (CS0, address 0).
# The PMOD flash is separate from the onboard ECP5 config flash, so offset=0
# means the start of the PMOD chip — TinyQV boots from there directly.
#
# Requires the flash-passthru bitstream to be loaded first so the JTAG/FTDI
# port can reach the PMOD SPI pins. See:
#   https://github.com/emard/ulx3s-misc/tree/master/examples/flash_passthru
flash-firmware: $(FIRMWARE_DIR)/triangle.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Writing firmware to PMOD flash (CS0, offset 0) ..."
	openFPGALoader -b ulx3s --file-type bin -f -o 0 $<
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Done."

$(FIRMWARE_DIR)/triangle.bin:
	$(MAKE) -C $(FIRMWARE_DIR) triangle

# Full deploy: synthesize + P&R + pack, then flash bitstream and firmware.
# After this, power-cycle the board → ECP5 loads from onboard flash →
# TinyQV boots from PMOD flash → triangle renders over UART.
triangle: borg.bit
	@echo -e "$(BOLD)[BUILD]$(RESET) Building triangle firmware ..."
	$(MAKE) -C $(FIRMWARE_DIR) triangle
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s -f borg.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing triangle firmware to PMOD flash ..."
	openFPGALoader -b ulx3s --file-type bin -f -o 0 $(FIRMWARE_DIR)/triangle.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Power-cycle board to run triangle."

# Print resource utilisation after synthesis
print_resources: yosys.log
	@LUTS=$$(grep -E '^\s+[0-9]+ +LUT4$$' yosys.log | tail -n1 | awk '{print $$1}'); \
	DFFS=$$(grep -E '^\s+[0-9]+ +TRELLIS_FF$$' yosys.log | tail -n1 | awk '{print $$1}'); \
	BRAM=$$(grep -E '^\s+[0-9]+ +DP16KD$$' yosys.log | tail -n1 | awk '{print $$1}'); \
	printf "Synthesis Estimate (Yosys / ECP5-85K):\n"; \
	printf "  LUT4:       %6d / %d (%3d%%)\n" $$LUTS $(MAX_LUT4) $$((LUTS * 100 / $(MAX_LUT4))); \
	printf "  TRELLIS_FF: %6d / %d (%3d%%)\n" $$DFFS $(MAX_DFF) $$((DFFS * 100 / $(MAX_DFF))); \
	printf "  BRAM (DP16KD): %3d / 208\n" $$BRAM; \
	if [ -f nextpnr.log ]; then \
		PNR_LUT=$$(sed -n 's/.*TRELLIS_SLICE:[[:space:]]*\([0-9]*\)\/.*/\1/p' nextpnr.log | tail -n1); \
		if [ -n "$$PNR_LUT" ]; then \
			printf "Final Utilization (nextpnr):\n"; \
			printf "  Slices:     %6d / %d (%3d%%)\n" $$PNR_LUT $(MAX_LUT4) $$((PNR_LUT * 100 / $(MAX_LUT4))); \
		fi; \
	fi

generate_verilog_ulx3s:
	@echo -e "$(BOLD)[CHISEL]$(RESET) Emitting ULX3S Verilog (BorgConfig.ULX3S) ..."
	$(MAKE) -C ../.. generate_verilog_ulx3s

# Catch debug output on the FT231X serial port
tio:
	tio /dev/ttyUSB0

# Install udev rules so non-root users can access the FT231X programmer.
# Run once on each new workstation (requires sudo).
install-udev:
	@echo -e "Installing /etc/udev/rules.d/80-fpga-ulx3s.rules ..."
	sudo cp host/80-fpga-ulx3s.rules /etc/udev/rules.d/
	sudo udevadm control --reload-rules
	sudo udevadm trigger
	@echo -e "Done. Replug the ULX3S to apply."

clean:
	rm -f borg.bit borg.config borg.json borg.svf yosys.log nextpnr.log .verilog_stamp_ulx3s

.PHONY: all help load flash \
        print_resources generate_verilog_ulx3s tio install-udev clean
