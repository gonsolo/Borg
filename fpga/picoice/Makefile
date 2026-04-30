# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

# Prevent parallel make from running two FPGA targets that share /dev/ttyACM0
.NOTPARALLEL:

BOLD := \033[1m
RESET   := \033[0m

# Rock-solid mpremote wrapper: lockfile + pre-flight + SIGTERM + auto-recovery
MPREMOTE := host/mpremote.sh

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  triangle:\t\tRender two triangles.$(RESET)"
	@echo -e "$(BOLD)  vkcube:\t\tRender a colored cube (12 triangles).$(RESET)"
	@echo -e "  burn:\t\t\tGenerate bitstream and flash to FPGA."
	@echo -e "  tio:\t\t\tConnect to the serial console."
	@echo -e "  reset:\t\tReset the CPU."
	@echo -e "  usb-recover:\t\tRecover hung pico-ice USB (no reboot)."
	@echo -e "  usb-check:\t\tCheck if pico-ice USB is responsive."
	@echo -e "  usb-preflight:\tCheck USB health + auto-recover before a run."
	@echo -e "  install-udev:\t\tInstall udev rules (run once on each new workstation)."
	@echo -e "  enable-bootsel:\tTemporarily allow /dev/sda for uf2 flashing (needs sudo)."
	@echo -e "  disable-bootsel:\tRe-block usb_storage for pico-ice BOOTSEL (needs sudo)."
	@echo -e "  borg.json:\t\tRun synthesis."
	@echo -e "  borg.asc:\t\tRun Place & Route (pnr)."
	@echo -e "  borg.bin:\t\tGenerate the FPGA bitstream."
	@echo -e "  print_resources:\tCheck LUT, DFF, carry, and BRAM usage."
	@echo -e "  test-all:\t\tRun all FPGA-side tests."
	@echo -e "  clean:\t\tRemove build artifacts."


######### Build hardware ###########################################################################

# All FPGA-parameterized Verilog is emitted by FpgaMain into out/fpga/verilog/.
# This includes BorgConfig.PicoIce variants (coordWidth=6, fifoDepth=1).
FPGA_VERILOG_DIR = ../../out/fpga/verilog

SRC_FILES = $$(find $(FPGA_VERILOG_DIR) -maxdepth 1 -name '*.sv' | tr '\n' ' ')

CHISEL_SRCS = $(wildcard ../../hardware/borg/src/*.scala) \
              $(wildcard ../../hardware/soc/src/*.scala) \
              $(wildcard ../../hardware/tinyqv/src/*.scala)

# Regenerate Verilog from Chisel when sources change
$(FPGA_VERILOG_DIR)/tinyQV_top.sv: $(CHISEL_SRCS)
	$(MAKE) -C ../.. generate_verilog

# Hardware limits
MAX_LUTS = 5280
MAX_DFFS = 5280

borg.json: $(FPGA_VERILOG_DIR)/tinyQV_top.sv
	@echo "Building netlist"
	@yosys -p "read -sv $(SRC_FILES); synth_ice40 -dsp -abc9 -device u -top tinyQV_top -json borg.json; stat -top tinyQV_top" -DICE40 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ > yosys.log

# Place and Route (P&R) — freq derived from MmioMap.CLOCK_MHZ via generated header
CLOCK_FREQ = $(shell grep -m1 'define CLOCK_MHZ' ../../software/borg/borg_sys.h | awk '{print $$3}')
borg.asc: borg.json
	nextpnr-ice40 --up5k --package sg48 --json borg.json --pcf ../../out/fpga/verilog/pico_ice.pcf --asc borg.asc \
		--freq $(CLOCK_FREQ) --log nextpnr.log --seed 0

# Generate FPGA bitstream
borg.bin: borg.asc
	icepack $< $@

# Burn to FPGA (uses rock-solid wrapper — umount is handled inside mpremote.sh)
# Use FORCE_REBURN=1 to re-flash even if .burned exists, e.g. after a USB hang
# that may have corrupted the previous flash:  make burn FORCE_REBURN=1
.burned: borg.bin ../../out/hardware/borg/rdl/borg_mmio.py
	$(MPREMOTE) 60 cp ../../out/hardware/borg/rdl/borg_mmio.py :borg_mmio.py
	$(MPREMOTE) 180 mount . + run host/program_bitstream.py
	@touch .burned

burn: usb-preflight
ifdef FORCE_REBURN
	@rm -f .burned
endif
	$(MAKE) .burned

# USB recovery targets
usb-recover:
	@../common/host/usb_recover.sh

usb-check:
	@../common/host/usb_recover.sh --check

# Pre-flight: check USB health and auto-recover before any deployment
# Depended upon by triangle/vkcube/burn so they always start from a good state
usb-preflight:
	@if ! ../common/host/usb_recover.sh --check 2>/dev/null; then \
		echo -e "$(BOLD)[PREFLIGHT]$(RESET) pico-ice not responsive — attempting recovery ..."; \
		../common/host/usb_recover.sh || (echo -e "\033[1;31m[PREFLIGHT] Recovery failed. Replug pico-ice.\033[0m" && exit 1); \
	fi
	@echo -e "$(BOLD)[PREFLIGHT]$(RESET) pico-ice OK"

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
triangle: usb-preflight .burned
	@$(MAKE) --no-print-directory -s -C ../../software/borg triangle
	@mkdir -p firmware_cache
	@cp ../../software/borg/triangle.bin ../../software/borg/test_texture.dat firmware_cache/
	@python3 ../../scripts/encode_texture.py ../../software/borg/test_texture.dat firmware_cache/test_texture_morton.bin
	@$(MPREMOTE) 300 mount .. + exec "import sys; sys.path.extend(['common/host', '/']); import render; render.run_single_frame(0, 'picoice/firmware_cache/triangle.bin')" | tee /tmp/mpremote_output.txt
	@python3 ../../scripts/postprocess.py triangle_00.bin triangle_00.ppm 32 32

# Build firmware and run vkcube rendering (always uses 64×64 small texture)
vkcube: usb-preflight .burned
	@$(MAKE) --no-print-directory -s -C ../../software/borg vkcube
	@mkdir -p firmware_cache
	@cp ../../software/borg/vkcube.bin firmware_cache/
	@cp ../../software/borg/borg_texture_small.dat firmware_cache/borg_texture.dat
	@python3 ../../scripts/encode_texture.py ../../software/borg/borg_texture_small.dat firmware_cache/borg_texture_morton.bin
	@$(MPREMOTE) 300 mount .. + exec "import sys; sys.path.extend(['common/host', '/']); import render; render.run_single_frame(0, 'picoice/firmware_cache/vkcube.bin')" | tee /tmp/mpremote_output.txt
	@python3 ../../scripts/postprocess.py vkcube_00.bin vkcube_00.ppm 32 32

# Catch debug output (preferably in a second shell)
tio:
	tio $$(ls /dev/serial/by-id/*Debug_Probe*if01* 2>/dev/null | head -n1 || echo /dev/ttyACM1) | tee /tmp/tio_output.txt

# Reset FPGA
reset: .burned
	$(MPREMOTE) 60 mount .. + exec "import sys; sys.path.extend(['common/host', '/']); import run_tinyqv; run_tinyqv.run(query=False, stop=False)"

clean:
	rm -f borg.bin borg.json borg.asc yosys.log nextpnr.log triangle*.ppm vkcube*.ppm triangle*.bin vkcube*.bin .burned
	$(MAKE) -C ../../software/borg clean
	$(MAKE) -C ../../software/tinyqv clean

# Install udev rules so usb_storage never deadlocks on pico-ice USB crashes.
# Run once on each new workstation (requires sudo).
install-udev:
	@echo "Installing /etc/udev/rules.d/99-pico-ice.rules ..."
	sudo cp host/99-pico-ice.rules /etc/udev/rules.d/
	sudo udevadm control --reload-rules
	sudo udevadm trigger
	@echo "Done. Replug the pico-ice to apply."

# Temporarily allow /dev/sda so you can flash firmware via UF2 drag-and-drop.
# Re-run 'make disable-bootsel' (or 'make install-udev') when done.
enable-bootsel:
	@echo "Temporarily allowing usb_storage for BOOTSEL mode ..."
	@sudo sed -i 's|^\(SUBSYSTEM=="usb".*idProduct=="0003".*DRIVER=="usb-storage".*\)|#\1  # temporarily disabled|' /etc/udev/rules.d/99-pico-ice.rules
	@sudo udevadm control --reload-rules && sudo udevadm trigger
	@echo "BOOTSEL /dev/sda enabled. Run 'make disable-bootsel' when done."

disable-bootsel:
	@echo "Re-blocking usb_storage for BOOTSEL mode ..."
	sudo cp host/99-pico-ice.rules /etc/udev/rules.d/
	sudo udevadm control --reload-rules
	sudo udevadm trigger
	@echo "BOOTSEL /dev/sda blocked again (kernel-safe)."

# Timing analysis on the synthesized bitstream
timing:
	icetime -d up5k -c $(shell grep 'CLOCK_MHZ' ../../software/borg/borg_sys.h | awk '{print $$3}') borg.asc

.PHONY: all help burn clean reset print_resources timing triangle vkcube tio \
        usb-recover usb-check usb-preflight install-udev enable-bootsel disable-bootsel
