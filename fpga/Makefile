# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

BOLD := \033[1m
RESET   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  triangle:\t\tRender two triangles.$(RESET)"
	@echo -e "$(BOLD)  vkcube:\t\tRender a colored cube (12 triangles).$(RESET)"
	@echo -e "  burn:\t\t\tGenerate bitstream and flash to FPGA."
	@echo -e "  tio:\t\t\tConnect to the serial console."
	@echo -e "  reset:\t\tReset the CPU."
	@echo -e "  borg.json:\t\tRun synthesis."
	@echo -e "  borg.asc:\t\tRun Place & Route (pnr)."
	@echo -e "  borg.bin:\t\tGenerate the FPGA bitstream."
	@echo -e "  print_resources:\tCheck LUT, DFF, carry, and BRAM usage."
	@echo -e "  test-all:\t\tRun all FPGA-side tests."
	@echo -e "  clean:\t\tRemove build artifacts."


######### Build hardware ###########################################################################

# 1. ASIC sources from Chisel (paths are already ../out/...)
# 2. Verilog generated from the scala source in fpga/tinyqv
FPGA_EXTRA_FILES = \
	../out/fpga/verilog/tinyQV_top.sv

SRC_FILES = $$(cat ../out/hardware/borg/verilog/asic_files.txt ../out/hardware/tinyqv/verilog/asic_files.txt | awk -F/ '!seen[$$NF]++' | tr '\n' ' ') $(FPGA_EXTRA_FILES)

CHISEL_SRCS = $(wildcard ../hardware/borg/src/*.scala) $(wildcard ../hardware/tinyqv/src/*.scala)

# Regenerate Verilog from Chisel when sources change
../out/hardware/borg/verilog/Borg.sv: $(CHISEL_SRCS)
	$(MAKE) -C .. generate_verilog

# Hardware limits
MAX_LUTS = 5280
MAX_DFFS = 5280

borg.json: ../out/hardware/borg/verilog/Borg.sv
	@echo "Building netlist"
	@yosys -p "read -sv $(SRC_FILES); synth_ice40 -dsp -abc9 -device u -top tinyQV_top -json borg.json; stat -top tinyQV_top" -DICE40 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ > yosys.log

# Place and Route (P&R) — freq derived from MmioMap.CLOCK_MHZ via generated header
CLOCK_FREQ = $(shell grep -m1 'define CLOCK_MHZ' ../software/borg/borg_sys.h | awk '{print $$3}')
borg.asc: borg.json
	nextpnr-ice40 --up5k --package sg48 --json borg.json --pcf ../out/fpga/verilog/pico_ice.pcf --asc borg.asc \
		--freq $(CLOCK_FREQ) --log nextpnr.log

# Generate FPGA bitstream
borg.bin: borg.asc
	icepack $< $@

# Burn to FPGA
.burned: borg.bin ../out/hardware/borg/rdl/borg_mmio.py
	@-umount /dev/sda 2>/dev/null; true
	mpremote cp ../out/hardware/borg/rdl/borg_mmio.py :borg_mmio.py
	@for attempt in 1 2 3; do \
		echo "[BURN] Attempt $$attempt ..."; \
		mpremote mount . + run host/program_bitstream.py && break; \
		echo "[BURN] Failed — waiting 8s for USB recovery ..."; \
		sleep 8; \
	done
	@touch .burned

burn: .burned

# Print used FPGA resources (after borg.json has been generated)
# LC estimate: each SB_CARRY requires its own LC (can't share with DFFs).
# Remaining LUTs and DFFs can share LCs, so effective total ≈ carries + max(luts-carries, dffs).
print_resources: yosys.log
	@LUTS=$$(grep "SB_LUT4" yosys.log | tail -n1 | awk '{print $$1}'); \
	DFFS=$$(grep "SB_DFF" yosys.log | tail -n7 | awk '{sum+=$$1} END{print sum}'); \
	CARRY=$$(grep "SB_CARRY" yosys.log | tail -n1 | awk '{print $$1}'); \
	BRAM=$$(grep "SB_RAM40_4K" yosys.log | tail -n1 | awk '{print $$1}'); \
	LC_EST=$$((CARRY + (LUTS - CARRY > DFFS ? LUTS - CARRY : DFFS))); \
	printf "$(BOLD)Synthesis Estimate (Yosys):$(RESET)\n"; \
	printf "  SB_LUT4:   %5d\n" $$LUTS; \
	printf "  SB_DFF:    %5d\n" $$DFFS; \
	printf "  SB_CARRY:  %5d\n" $$CARRY; \
	printf "  SB_RAM40:  %5d / 30\n" $$BRAM; \
	printf "  Est. LC:   %5d / %d (%3d%%)\n" $$LC_EST $(MAX_LUTS) $$((LC_EST * 100 / $(MAX_LUTS))); \
	if [ -f nextpnr.log ]; then \
		PNR_LC=$$(sed -n 's/.*ICESTORM_LC:[[:space:]]*\([0-9]*\)\/.*/\1/p' nextpnr.log); \
		PNR_RAM=$$(sed -n 's/.*ICESTORM_RAM:[[:space:]]*\([0-9]*\)\/.*/\1/p' nextpnr.log); \
		if [ -n "$$PNR_LC" ]; then \
			printf "$(BOLD)Final Utilization (nextpnr):$(RESET)\n"; \
			printf "  LCs:       %5d / %d (%3d%%)\n" $$PNR_LC $(MAX_LUTS) $$((PNR_LC * 100 / $(MAX_LUTS))); \
			printf "  BRAMs:     %5d / 30 (%3d%%)\n" $$PNR_RAM $$((PNR_RAM * 100 / 30)); \
			LC_VAL=$$PNR_LC; \
		else \
			LC_VAL=$$LC_EST; \
		fi; \
	else \
		LC_VAL=$$LC_EST; \
	fi; \
	if [ $$LC_VAL -gt $(MAX_LUTS) ]; then \
		printf "\033[1;31m  ⚠  OVER LIMIT by %d LCs\033[0m\n" $$((LC_VAL - $(MAX_LUTS))); \
	elif [ $$((LC_VAL * 100 / $(MAX_LUTS))) -gt 95 ]; then \
		printf "\033[1;33m  ⚠  Tight fit (>95%%)\033[0m\n"; \
	else \
		printf "\033[1;32m  ✓  Should fit\033[0m\n"; \
	fi

######### Build software and run ###################################################################

# Build firmware and run triangle rendering
triangle: .burned
	@sleep 2
	@$(MAKE) --no-print-directory -s -C ../software/borg triangle
	@mkdir -p firmware_cache
	@cp ../software/borg/triangle.bin ../software/borg/test_texture.dat firmware_cache/
	@mpremote mount . + exec "import sys; sys.path.extend(['host', '/']); import render; render.run_single_frame(0, 'firmware_cache/triangle.bin')" | tee /tmp/mpremote_output.txt

# Build firmware and run vkcube rendering
vkcube: .burned
	@sleep 2
	@$(MAKE) --no-print-directory -s -C ../software/borg vkcube
	@mkdir -p firmware_cache
	@cp ../software/borg/vkcube.bin ../software/borg/borg_texture.dat firmware_cache/
	@mpremote mount . + exec "import sys; sys.path.extend(['host', '/']); import render; render.run_single_frame(0, 'firmware_cache/vkcube.bin')" | tee /tmp/mpremote_output.txt

# Catch debug output (preferably in a second shell)
tio:
	tio /dev/ttyACM1 | tee /tmp/tio_output.txt

# Reset FPGA
reset: .burned
	mpremote mount . + exec "import sys; sys.path.extend(['host', '/']); import run_tinyqv; run_tinyqv.run(query=False, stop=False)"

clean:
	rm -f borg.bin borg.json borg.asc yosys.log nextpnr.log triangle*.ppm .burned
	$(MAKE) -C ../software/borg clean
	$(MAKE) -C ../software/tinyqv clean

# Timing analysis on the synthesized bitstream
timing:
	icetime -d up5k -c $(shell grep 'CLOCK_MHZ' ../software/borg/borg_sys.h | awk '{print $$3}') borg.asc

.PHONY: all help burn clean reset print_resources timing triangle vkcube tio
