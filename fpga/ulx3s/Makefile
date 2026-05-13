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
	@echo -e "  borg.config:\t\tRun Place \u0026 Route (nextpnr-ecp5)."
	@echo ""
	@echo -e "$(BOLD)Hardware programming:$(RESET)"
	@echo -e "  flash:\t\tFlash ECP5 bitstream to onboard config flash."
	@echo -e "  flash-firmware:\tFlash firmware (with size header) to flash offset 0x400000."
	@echo -e "  minimal-boot:\t\tBuild + flash a minimal firmware — tests FlashBootLoader (D.3)."
	@echo -e "  triangle:\t\tBuild + flash bitstream + triangle firmware. Power-cycle to run."
	@echo ""
	@echo -e "$(BOLD)Utilities:$(RESET)"
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
              $(wildcard ../../hardware/memory/src/*.scala) \
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

# Clock frequency — 125 MHz (PLL output from 25 MHz oscillator)
CLOCK_FREQ = 125

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

# Write bitstream to onboard config flash (persistent, survives power cycle)
flash: borg.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Bitstream flashed."

######### Firmware (onboard SDRAM boot flow) ######################################################

FIRMWARE_DIR = ../../software/borg

# Flash firmware requires a 4-byte little-endian size header prepended.
# The FlashBootLoader reads this header first to know how many bytes to copy.
# firmware_with_header.bin = [uint32_le size] + [firmware bytes]
firmware_with_header.bin: $(FIRMWARE_DIR)/triangle.bin
	@echo -e "$(BOLD)[FW-PACK]$(RESET) Prepending 4-byte size header to firmware ..."
	@python3 -c "import struct; d=open('$(FIRMWARE_DIR)/triangle.bin','rb').read(); open('firmware_with_header.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + 4-byte header -> firmware_with_header.bin')"

$(FIRMWARE_DIR)/triangle.bin:
	$(MAKE) -C $(FIRMWARE_DIR) triangle

# Flash firmware blob to onboard flash at 0x400000.
# FlashBootLoader will copy it to SDRAM at boot.
flash-firmware: firmware_with_header.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Writing firmware to onboard flash @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 firmware_with_header.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Firmware flashed at 0x400000."

# Minimal boot test (D.3): tiny 4-byte RISC-V infinite loop + size header.
# Boot LED[6] = boot_done lights up if FlashBootLoader completes successfully.
minimal.bin:
	@echo -e "$(BOLD)[FW-PACK]$(RESET) Building minimal boot-test firmware (4-byte infinite loop) ..."
	@python3 -c "import struct; fw=bytes([0x6f,0x00,0x00,0x00]); open('minimal.bin','wb').write(struct.pack('<I',len(fw))+fw); print('  minimal.bin: 8 bytes (4-byte header + JAL x0,0)')"

minimal-boot: borg.bit minimal.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing ECP5 bitstream ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing minimal firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 minimal.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Power-cycle the board."
	@echo -e "  LED[7] = pll_locked (lights immediately)"
	@echo -e "  LED[6] = boot_done  (lights after ~1 ms — FlashBootLoader done)"

# Full triangle deploy: synth + P&R + pack, flash bitstream + firmware.
# After power-cycle: ECP5 loads bitstream -> FlashBootLoader copies firmware
# to SDRAM -> TinyQV runs -> Borg renders triangle -> UART dumps pixels.
triangle: borg.bit firmware_with_header.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing triangle firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 firmware_with_header.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Both flashed. Power-cycle board to run."
	@echo -e "  LED[7] = pll_locked, LED[6] = boot_done"
	@echo -e "  After boot: connect with 'make tio' to capture triangle pixels."

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

.PHONY: all help load flash flash-firmware minimal-boot triangle \
        print_resources generate_verilog_ulx3s tio install-udev clean
