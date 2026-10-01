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
	@echo -e "  deploy:\t\tBuild + flash bitstream + kernel firmware. Power-cycle to run,"
	@echo -e "\t\t\tthen run ../run-vkcube.sh on the host (see run-vkcube.sh)."
	@echo -e "  reload:\t\tRebuild kernel.bin and stream it over serial (no reflash/reconfig)."
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
              $(wildcard ../../hardware/borg/src/link/*.scala) \
              $(wildcard ../../hardware/borg/src/generated/*.scala) \
              $(wildcard ../../hardware/hutt/src/*.scala) \
              $(wildcard ../../hardware/memory/src/*.scala) \
              $(wildcard ../../hardware/peri/src/*.scala) \
              $(wildcard ../../hardware/peri/src/uart/*.scala) \
              $(wildcard ../../hardware/soc/src/*.scala) \
              $(wildcard soc/src/*.scala)

# ── Single source of truth for the ULX3S clock (MHz) ────────────────────────
# Drives the PLL SoC output (SOC_MHZ = CLOCK_MHZ in ULX3S.scala), the debug-UART
# baud divider (SoCLogic), AND the firmware's CLOCK_MHZ — set it here only.
# The HDMI pixel clock is generated inside the 125 MHz HDMI domain (a
# divide-by-5 clock enable), not by this clock, so this is free of the video
# timing. 10 MHz, not the ~17 MHz the FP32 draw-front-end build can actually
# route at: this PLL's other targets near 15-18 MHz all solve to a 750 MHz
# VCO, which reliably failed to lock on a genuine cold boot from SPI flash on
# real hardware (confirmed repeatedly with a small diagnostic bitstream,
# HdmiTestPattern3Pll.scala) even though it worked loaded via JTAG. 10 MHz is
# the fastest integer MHz target that lands exactly on a VCO (500 MHz)
# confirmed safe on the same hardware. See ULX3S.scala's PLL comment.
# Staged reintroduction: HDMI_DECOUPLE=0 (default) is the HPG 2026 wiring (pixel clock
# == SoC clock, 25 MHz). HDMI_DECOUPLE=1 is the divide-by-5 video clock + dual-clock
# frame buffer, which needs the SoC clock at 10 MHz (PLL cold-boot VCO, see above).
HDMI_DECOUPLE ?= 0
BORG_SAMPLES ?=
BORG_MAXBINTILES ?=
BORG_ISA_EXT ?=
BORG_ICACHE ?=
export HDMI_DECOUPLE BORG_SAMPLES BORG_MAXBINTILES BORG_ISA_EXT BORG_ICACHE
ULX3S_MHZ ?= $(if $(filter 1,$(HDMI_DECOUPLE)),10,25)

# Changing HDMI_DECOUPLE must re-emit the Verilog: keep exactly one flag file.
.hdmi_decouple_$(HDMI_DECOUPLE)_$(ULX3S_MHZ)_$(BORG_SAMPLES)_$(BORG_MAXBINTILES)_$(BORG_ISA_EXT)_$(BORG_ICACHE):
	@rm -f .hdmi_decouple_*; touch $@

# Stamp file — regenerates Verilog only when Chisel sources OR Makefiles change.
# Makefiles are included so CLOCK_MHZ changes don't silently use stale Verilog.
.verilog_stamp_ulx3s: $(CHISEL_SRCS) Makefile ../../Makefile .hdmi_decouple_$(HDMI_DECOUPLE)_$(ULX3S_MHZ)_$(BORG_SAMPLES)_$(BORG_MAXBINTILES)_$(BORG_ISA_EXT)_$(BORG_ICACHE)
	$(MAKE) -C ../.. generate_verilog_ulx3s ULX3S_CLOCK_MHZ=$(ULX3S_MHZ)
	@touch $@

# LPF emitted alongside the Verilog by ULX3SMain
LPF = $(ULX3S_VERILOG_DIR)/ulx3s.lpf

# ECP5-85K resource limits
MAX_LUT4  = 83640
MAX_DFF   = 83640

# Synthesis — ECP5 target, ABC9 enabled
borg.json: .verilog_stamp_ulx3s
	@echo -e "$(BOLD)[SYNTH]$(RESET) Running Yosys for ECP5 ..."
	yosys -p "read -sv $(SRC_FILES); \
	           read_verilog -sv -lib $(CURDIR)/primitives/ecp5_prims.v; \
	           synth_ecp5 -abc9 -top ulx3s_top -json borg.json; \
	           stat -top ulx3s_top" \
	      -DECP5 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ \
	      > yosys.log
	@echo -e "$(BOLD)[SYNTH]$(RESET) Done."

# Clock frequency — 25 MHz SoC clock (HDMI runs at 125 MHz separately)
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

# Write bitstream to onboard config flash (persistent, survives power cycle).
# Does NOT reset — run 'make reset' (with tio already open) to boot the new image.
flash: borg.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset borg.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Bitstream flashed. Start tio, then: make reset"

# Reset board from flash — run with tio already open to capture boot output.
.PHONY: reset
reset:
	openFPGALoader -b ulx3s -r

######### Firmware (onboard SDRAM boot flow) ######################################################

FIRMWARE_DIR = ../../software/borg

# Flash firmware requires a 4-byte little-endian size header prepended.
# The FlashBootLoader reads this header first to know how many bytes to copy.
# firmware_with_header.bin = [uint32_le size] + [firmware bytes]
firmware_with_header.bin: $(FIRMWARE_DIR)/kernel.bin
	@echo -e "$(BOLD)[FW-PACK]$(RESET) Prepending 4-byte size header to firmware ..."
	@python3 -c "import struct; d=open('$(FIRMWARE_DIR)/kernel.bin','rb').read(); open('firmware_with_header.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + 4-byte header -> firmware_with_header.bin')"

# -DTARGET_ULX3S selects the ULX3S address map (UART_BASE = SoC debug UART,
# DRAM_BASE = SDRAM) and disables the simulation done-wait in borg_kernel.c.
.PHONY: $(FIRMWARE_DIR)/kernel.bin
$(FIRMWARE_DIR)/kernel.bin:
	$(MAKE) -C $(FIRMWARE_DIR) CLOCK_MHZ=$(ULX3S_MHZ) \
		EXTRA_CFLAGS="-DTARGET_ULX3S" kernel

# Flash firmware blob to onboard flash at 0x400000.
# FlashBootLoader will copy it to SDRAM at boot.
flash-firmware: firmware_with_header.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Writing firmware to onboard flash @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 firmware_with_header.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Firmware flashed. Resetting FPGA ..."
	openFPGALoader -b ulx3s -r
	@echo -e "$(BOLD)[DONE]$(RESET) Board is rebooting — attach tio now."

# Minimal boot test (D.3): tiny 4-byte RISC-V infinite loop + size header.
# Boot LED[6] = boot_done lights up if FlashBootLoader completes successfully.
# UART Hello firmware: sends 'Z' repeatedly via debug UART (0x08000018).
uart_hello_fw.bin: uart_hello.s
	@echo -e "$(BOLD)[FW]$(RESET) Assembling uart_hello.s ..."
	riscv64-unknown-elf-as -march=rv32i -mabi=ilp32 -o uart_hello.o uart_hello.s
	riscv64-unknown-elf-ld -melf32lriscv -Ttext=0x0 -o uart_hello.elf uart_hello.o
	riscv64-unknown-elf-objcopy -O binary uart_hello.elf uart_hello_raw.bin
	@python3 -c "import struct; d=open('uart_hello_raw.bin','rb').read(); open('uart_hello_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> uart_hello_fw.bin')"

flash-uart-hello: uart_hello_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing uart_hello firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 uart_hello_fw.bin

sdram_fb_fw.bin: sdram_fb.s
	@echo -e "$(BOLD)[FW]$(RESET) Assembling sdram_fb.s ..."
	riscv64-unknown-elf-as -march=rv32i -mabi=ilp32 -o sdram_fb.o sdram_fb.s
	riscv64-unknown-elf-ld -melf32lriscv -Ttext=0x0 -o sdram_fb.elf sdram_fb.o
	riscv64-unknown-elf-objcopy -O binary sdram_fb.elf sdram_fb_raw.bin
	@python3 -c "import struct; d=open('sdram_fb_raw.bin','rb').read(); open('sdram_fb_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> sdram_fb_fw.bin')"

flash-sdram-fb: sdram_fb_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing sdram_fb firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 sdram_fb_fw.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Firmware flashed. Now run: make load && make tio"

sdram_rw_test_fw.bin: sdram_rw_test.s
	@echo -e "$(BOLD)[FW]$(RESET) Assembling sdram_rw_test.s ..."
	riscv64-unknown-elf-as -march=rv32i -mabi=ilp32 -o sdram_rw_test.o sdram_rw_test.s
	riscv64-unknown-elf-ld -melf32lriscv -Ttext=0x0 -o sdram_rw_test.elf sdram_rw_test.o
	riscv64-unknown-elf-objcopy -O binary sdram_rw_test.elf sdram_rw_test_raw.bin
	@python3 -c "import struct; d=open('sdram_rw_test_raw.bin','rb').read(); open('sdram_rw_test_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> sdram_rw_test_fw.bin')"

flash-sdram-rw-test: sdram_rw_test_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing sdram_rw_test firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 sdram_rw_test_fw.bin

minimal-sdram-rw-test: borg-minimal.bit sdram_rw_test_fw.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing minimal bitstream ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg-minimal.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing sdram_rw_test firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 sdram_rw_test_fw.bin

# Bare-metal RV64 SDRAM torture test (task #15 "Bug A" diagnostic — see
# sdram_torture/sdram_torture.c's header for the full writeup and project
# memory for the investigation). No OpenSBI/Linux/MMU: FlashBootLoader
# copies this straight to SDRAM address 0 and jumps there in M-mode, same
# entry convention OpenSBI itself uses. Needs the RV64+CLINT minimal-linux
# bitstream already loaded (`make generate_verilog_ulx3s_minimal_linux` in
# the top Makefile, then the normal ULX3S synth/PnR/load flow) — this
# firmware alone does not configure the FPGA.
#
# Conclusion as of the 2026-07-10 investigation (bare-metal test, before
# the real Bug A root cause — a CSPRNG entropy-wait, see [[project_hutt_linux_status]]
# — was found): the raw SDRAM/MemoryController/interrupt-arbitration path
# is robust under dense synthetic stress (sequential+random addressing,
# mixed access sizes, forced icache misses, async CLINT interrupts) —
# 20+ iterations, 337,000+ interrupts, zero corruption/hangs. Kept as a
# reusable regression tool for future real-SDRAM-path changes, not as an
# open investigation.
SDRAM_TORTURE_DIR := sdram_torture
SDRAM_TORTURE_ARCH := rv64ima_zicsr_zifencei

sdram_torture_fw.bin: $(SDRAM_TORTURE_DIR)/sdram_torture.c $(SDRAM_TORTURE_DIR)/start.s $(SDRAM_TORTURE_DIR)/trap.s $(SDRAM_TORTURE_DIR)/link.ld
	@echo -e "$(BOLD)[FW]$(RESET) Building sdram_torture (RV64IMA, M-mode, no OpenSBI) ..."
	riscv64-unknown-elf-gcc -march=$(SDRAM_TORTURE_ARCH) -mabi=lp64 -mcmodel=medany -O2 -ffreestanding -nostdlib -Wall -Wextra \
		-c -o sdram_torture.o $(SDRAM_TORTURE_DIR)/sdram_torture.c
	riscv64-unknown-elf-as -march=$(SDRAM_TORTURE_ARCH) -mabi=lp64 -o start.o $(SDRAM_TORTURE_DIR)/start.s
	riscv64-unknown-elf-as -march=$(SDRAM_TORTURE_ARCH) -mabi=lp64 -o trap.o $(SDRAM_TORTURE_DIR)/trap.s
	riscv64-unknown-elf-ld -T $(SDRAM_TORTURE_DIR)/link.ld -o sdram_torture.elf start.o trap.o sdram_torture.o
	riscv64-unknown-elf-objcopy -O binary sdram_torture.elf sdram_torture_raw.bin
	@python3 -c "import struct; d=open('sdram_torture_raw.bin','rb').read(); open('sdram_torture_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> sdram_torture_fw.bin')"

flash-sdram-torture: sdram_torture_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing sdram_torture firmware @ 0x400000 (needs the RV64+CLINT minimal-linux bitstream already loaded) ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 sdram_torture_fw.bin

hdmi_test_fw.bin: hdmi_test_fb.s
	@echo -e "$(BOLD)[FW]$(RESET) Assembling hdmi_test_fb.s ..."
	riscv64-unknown-elf-as -march=rv32i -mabi=ilp32 -o hdmi_test_fb.o hdmi_test_fb.s
	riscv64-unknown-elf-ld -melf32lriscv -Ttext=0x0 -o hdmi_test_fb.elf hdmi_test_fb.o
	riscv64-unknown-elf-objcopy -O binary hdmi_test_fb.elf hdmi_test_raw.bin
	@python3 -c "import struct; d=open('hdmi_test_raw.bin','rb').read(); open('hdmi_test_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> hdmi_test_fw.bin')"

flash-hdmi-test: hdmi_test_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing hdmi_test firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 hdmi_test_fw.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Firmware flashed. Power-cycle and connect with: make tio"

hdmi-test: borg.bit hdmi_test_fw.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing ECP5 bitstream ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing hdmi_test firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 hdmi_test_fw.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Power-cycle. Expect: red 32x32 overlay on HDMI, 'R' then 'D' on tio."

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

kernel_with_header.bin: $(FIRMWARE_DIR)/kernel.bin
	@echo -e "$(BOLD)[FW-PACK]$(RESET) Prepending 4-byte size header to kernel firmware ..."
	@python3 -c "import struct; d=open('$(FIRMWARE_DIR)/kernel.bin','rb').read(); open('kernel_with_header.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + 4-byte header -> kernel_with_header.bin')"

flash-kernel: kernel_with_header.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Writing kernel firmware to onboard flash @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset -o 0x400000 kernel_with_header.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Kernel firmware flashed. Start tio, then: make reset"

# Serial firmware reload — NO reflash, NO FPGA reconfig, so the HDMI monitor
# never loses sync.  Rebuilds kernel.bin and streams it to the running firmware
# over /dev/ttyUSB0; the firmware warm-resets and reboots into it.
.PHONY: reload
reload:
	@echo -e "$(BOLD)[RELOAD]$(RESET) Rebuilding kernel.bin ..."
	$(MAKE) -C $(FIRMWARE_DIR) CLOCK_MHZ=$(ULX3S_MHZ) \
		EXTRA_CFLAGS="-DTARGET_ULX3S" kernel
	@echo -e "$(BOLD)[RELOAD]$(RESET) Streaming kernel.bin over serial (no reflash) ..."
	python3 host/reload.py /dev/ttyUSB0 $(FIRMWARE_DIR)/kernel.bin

# Full kernel deploy: synth + P&R + pack, flash bitstream + firmware.
# After power-cycle: ECP5 loads bitstream → FlashBootLoader copies firmware
# to SDRAM → Hutt boots → kernel waits for borgvk → cube.c renders via GPU.
deploy: borg.bit firmware_with_header.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing kernel firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 firmware_with_header.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Both flashed. Power-cycle board to run."
	@echo -e "  LED[7] = pll_locked, LED[6] = boot_done"
	@echo -e "  After boot: run cube with BORGVK_SERIAL=/dev/ttyUSB0 ./cube"

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
	$(MAKE) -C ../.. generate_verilog_ulx3s ULX3S_CLOCK_MHZ=$(ULX3S_MHZ)

######### Rung A: Borg reached only through the link RTL (loopback) ################################

LOOPBACK_VERILOG_DIR = ../../out/ulx3s/verilog_loopback
LOOPBACK_SRC_FILES = $$(find $(LOOPBACK_VERILOG_DIR) -maxdepth 1 -name '*.sv' | tr '\n' ' ')
LOOPBACK_LPF = $(LOOPBACK_VERILOG_DIR)/ulx3s.lpf

.verilog_stamp_ulx3s_loopback: $(CHISEL_SRCS) Makefile ../../Makefile
	$(MAKE) -C ../.. generate_verilog_ulx3s_loopback ULX3S_CLOCK_MHZ=$(ULX3S_MHZ)
	@touch $@

borg-loopback.json: .verilog_stamp_ulx3s_loopback
	@echo -e "$(BOLD)[SYNTH]$(RESET) Running Yosys for loopback SoC ..."
	yosys -p "read -sv $(LOOPBACK_SRC_FILES); \
	           read_verilog -sv -lib $(CURDIR)/primitives/ecp5_prims.v; \
	           synth_ecp5 -abc9 -top ulx3s_top -json borg-loopback.json; \
	           stat -top ulx3s_top" \
	      -DECP5 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ \
	      > yosys-loopback.log
	@echo -e "$(BOLD)[SYNTH]$(RESET) Done."

borg-loopback.config: borg-loopback.json
	@echo -e "$(BOLD)[PNR]$(RESET) Running nextpnr-ecp5 for loopback SoC ..."
	nextpnr-ecp5 \
	  --85k --package CABGA381 \
	  --json borg-loopback.json \
	  --lpf $(LOOPBACK_LPF) \
	  --textcfg borg-loopback.config \
	  --freq $(CLOCK_FREQ) \
	  --timing-allow-fail \
	  --log nextpnr-loopback.log \
	  --seed 0
	@echo -e "$(BOLD)[PNR]$(RESET) Done."

borg-loopback.bit: borg-loopback.config
	@echo -e "$(BOLD)[PACK]$(RESET) Packing loopback bitstream ..."
	ecppack --svf borg-loopback.svf borg-loopback.config borg-loopback.bit
	@echo -e "$(BOLD)[PACK]$(RESET) Done — borg-loopback.bit ready."

# Same kernel firmware as the direct-Borg demo -- rung A's whole point is that
# firmware/borgvk/HDMI scanout are unchanged; only how Borg is reached differs.
flash-loopback: borg-loopback.bit kernel_with_header.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing loopback ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset borg-loopback.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Writing kernel firmware to onboard flash @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset -o 0x400000 kernel_with_header.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Both flashed. Start tio, then: make reset"

######### Rungs B/C: Borg reached through the link RTL over real pins ##############################

EXTERNAL_VERILOG_DIR = ../../out/ulx3s/verilog_external
EXTERNAL_SRC_FILES = $$(find $(EXTERNAL_VERILOG_DIR) -maxdepth 1 -name '*.sv' | tr '\n' ' ')
EXTERNAL_LPF = $(EXTERNAL_VERILOG_DIR)/ulx3s.lpf

.verilog_stamp_ulx3s_external: $(CHISEL_SRCS) Makefile ../../Makefile
	$(MAKE) -C ../.. generate_verilog_ulx3s_external ULX3S_CLOCK_MHZ=$(ULX3S_MHZ)
	@touch $@

borg-external.json: .verilog_stamp_ulx3s_external
	@echo -e "$(BOLD)[SYNTH]$(RESET) Running Yosys for external-link SoC ..."
	yosys -p "read -sv $(EXTERNAL_SRC_FILES); \
	           read_verilog -sv -lib $(CURDIR)/primitives/ecp5_prims.v; \
	           synth_ecp5 -abc9 -top ulx3s_top -json borg-external.json; \
	           stat -top ulx3s_top" \
	      -DECP5 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ \
	      > yosys-external.log
	@echo -e "$(BOLD)[SYNTH]$(RESET) Done."

# Placement-only check (Phase 5): confirms nextpnr can actually place all ~40
# link pins + 4 DIP-switch straps onto real CABGA381 sites alongside every
# other SoC pin, with no site conflicts. Not yet flashed to hardware --
# that's rung B, and needs the physical ribbon cable jumpered first.
borg-external.config: borg-external.json
	@echo -e "$(BOLD)[PNR]$(RESET) Running nextpnr-ecp5 for external-link SoC ..."
	nextpnr-ecp5 \
	  --85k --package CABGA381 \
	  --json borg-external.json \
	  --lpf $(EXTERNAL_LPF) \
	  --textcfg borg-external.config \
	  --freq $(CLOCK_FREQ) \
	  --timing-allow-fail \
	  --log nextpnr-external.log \
	  --seed 0
	@echo -e "$(BOLD)[PNR]$(RESET) Done."

borg-external.bit: borg-external.config
	@echo -e "$(BOLD)[PACK]$(RESET) Packing external-link bitstream ..."
	ecppack --svf borg-external.svf borg-external.config borg-external.bit
	@echo -e "$(BOLD)[PACK]$(RESET) Done — borg-external.bit ready."

# Same kernel firmware as the direct-Borg/rung-A demos -- rung B's whole point
# is that firmware/borgvk/HDMI scanout are unchanged; only how Borg is reached
# differs, and now it's reached over the real physical J1/J2 ribbon loom
# (dn_* -> jumper -> up_*) instead of internal wires.
flash-external: borg-external.bit kernel_with_header.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing external-link ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset borg-external.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Writing kernel firmware to onboard flash @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset -o 0x400000 kernel_with_header.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Both flashed. Start tio, then: make reset"

######### Rung B: the whole bridge, master<->slave routed out over pads #########################

# Unlike borg-external (master only -- the far side lives elsewhere, rung C),
# this build has master + slave + a real Borg, with every link wire leaving the
# FPGA and coming back over the J1/J2 ribbon. Narrow w=8: two pads per logical
# wire is 46 holes, which fits; w=16 would need 78. See BorgMode's doc.
PADLOOP_VERILOG_DIR = ../../out/ulx3s/verilog_padloop
PADLOOP_SRC_FILES = $$(find $(PADLOOP_VERILOG_DIR) -maxdepth 1 -name '*.sv' | tr '\n' ' ')
PADLOOP_LPF = $(PADLOOP_VERILOG_DIR)/ulx3s.lpf

.verilog_stamp_ulx3s_padloop: $(CHISEL_SRCS) Makefile ../../Makefile
	$(MAKE) -C ../.. generate_verilog_ulx3s_padloop ULX3S_CLOCK_MHZ=$(ULX3S_MHZ)
	@touch $@

borg-padloop.json: .verilog_stamp_ulx3s_padloop
	@echo -e "$(BOLD)[SYNTH]$(RESET) Running Yosys for pad-loop (rung B) SoC ..."
	yosys -p "read -sv $(PADLOOP_SRC_FILES); \
	           read_verilog -sv -lib $(CURDIR)/primitives/ecp5_prims.v; \
	           synth_ecp5 -abc9 -top ulx3s_top -json borg-padloop.json; \
	           stat -top ulx3s_top" \
	      -DECP5 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ \
	      > yosys-padloop.log
	@echo -e "$(BOLD)[SYNTH]$(RESET) Done."

borg-padloop.config: borg-padloop.json
	@echo -e "$(BOLD)[PNR]$(RESET) Running nextpnr-ecp5 for pad-loop SoC ..."
	nextpnr-ecp5 \
	  --85k --package CABGA381 \
	  --json borg-padloop.json \
	  --lpf $(PADLOOP_LPF) \
	  --textcfg borg-padloop.config \
	  --freq $(CLOCK_FREQ) \
	  --timing-allow-fail \
	  --log nextpnr-padloop.log \
	  --seed 0
	@echo -e "$(BOLD)[PNR]$(RESET) Done."

borg-padloop.bit: borg-padloop.config
	@echo -e "$(BOLD)[PACK]$(RESET) Packing pad-loop bitstream ..."
	ecppack --svf borg-padloop.svf borg-padloop.config borg-padloop.bit
	@echo -e "$(BOLD)[PACK]$(RESET) Done — borg-padloop.bit ready."

# Same kernel firmware as every other rung -- only how Borg is reached differs.
flash-padloop: borg-padloop.bit kernel_with_header.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing pad-loop ECP5 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset borg-padloop.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Writing kernel firmware to onboard flash @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset -o 0x400000 kernel_with_header.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Both flashed. Start tio, then: make reset"

######### Minimal SoC (Hutt + UART only) — fast iteration ##########################################

ULX3S_MINIMAL_VERILOG_DIR = ../../out/ulx3s_minimal/verilog
MIN_SRC_FILES = $$(find $(ULX3S_MINIMAL_VERILOG_DIR) -maxdepth 1 -name '*.sv' | tr '\n' ' ')
MIN_LPF = $(ULX3S_MINIMAL_VERILOG_DIR)/ulx3s.lpf

.verilog_stamp_ulx3s_minimal: $(CHISEL_SRCS) Makefile ../../Makefile
	$(MAKE) -C ../.. generate_verilog_ulx3s_minimal
	@touch $@

borg-minimal.json: .verilog_stamp_ulx3s_minimal
	@echo -e "$(BOLD)[SYNTH]$(RESET) Running Yosys for minimal SoC ..."
	yosys -p "read -sv $(MIN_SRC_FILES); \
	           read_verilog -sv -lib $(CURDIR)/primitives/ecp5_prims.v; \
	           synth_ecp5 -abc9 -top ulx3s_minimal_top -json borg-minimal.json; \
	           stat -top ulx3s_minimal_top" \
	      -DECP5 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ \
	      > yosys-minimal.log
	@echo -e "$(BOLD)[SYNTH]$(RESET) Done."

borg-minimal.config: borg-minimal.json
	@echo -e "$(BOLD)[PNR]$(RESET) Running nextpnr-ecp5 for minimal SoC ..."
	nextpnr-ecp5 \
	  --85k --package CABGA381 \
	  --json borg-minimal.json \
	  --lpf $(MIN_LPF) \
	  --textcfg borg-minimal.config \
	  --freq $(CLOCK_FREQ) \
	  --timing-allow-fail \
	  --log nextpnr-minimal.log \
	  --seed 0
	@echo -e "$(BOLD)[PNR]$(RESET) Done."

borg-minimal.bit: borg-minimal.config
	@echo -e "$(BOLD)[PACK]$(RESET) Packing minimal bitstream ..."
	ecppack --svf borg-minimal.svf borg-minimal.config borg-minimal.bit
	@echo -e "$(BOLD)[PACK]$(RESET) Done — borg-minimal.bit ready."

# Combined target: flash minimal bitstream + uart_hello firmware.
minimal-uart: borg-minimal.bit uart_hello_fw.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing minimal ECP5 bitstream ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg-minimal.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing uart_hello firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 uart_hello_fw.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Power-cycle the board, then 'make tio'."

hdmi_tiled_fw.bin: hdmi_tiled_fw.s
	@echo -e "$(BOLD)[FW]$(RESET) Assembling hdmi_tiled_fw.s ..."
	riscv64-unknown-elf-as -march=rv32i -mabi=ilp32 -o hdmi_tiled_fw.o hdmi_tiled_fw.s
	riscv64-unknown-elf-ld -melf32lriscv -Ttext=0x0 -o hdmi_tiled_fw.elf hdmi_tiled_fw.o
	riscv64-unknown-elf-objcopy -O binary hdmi_tiled_fw.elf hdmi_tiled_fw_raw.bin
	@python3 -c "import struct; d=open('hdmi_tiled_fw_raw.bin','rb').read(); open('hdmi_tiled_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> hdmi_tiled_fw.bin')"

flash-hdmi-tiled: hdmi_tiled_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing hdmi_tiled firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 hdmi_tiled_fw.bin

# Combined target: flash minimal bitstream + HDMI tiled firmware.
minimal-hdmi: borg-minimal.bit hdmi_tiled_fw.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing minimal ECP5 bitstream ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg-minimal.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing hdmi_tiled firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 hdmi_tiled_fw.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Power-cycle, connect HDMI. Expect: red 32x32 overlay. Run 'make tio' for 'R' confirmation."

hdmi_fill_fw.bin: hdmi_fill_fw.s
	@echo -e "$(BOLD)[FW]$(RESET) Assembling hdmi_fill_fw.s ..."
	riscv64-unknown-elf-as -march=rv32i -mabi=ilp32 -o hdmi_fill_fw.o hdmi_fill_fw.s
	riscv64-unknown-elf-ld -melf32lriscv -Ttext=0x0 -o hdmi_fill_fw.elf hdmi_fill_fw.o
	riscv64-unknown-elf-objcopy -O binary hdmi_fill_fw.elf hdmi_fill_fw_raw.bin
	@python3 -c "import struct; d=open('hdmi_fill_fw_raw.bin','rb').read(); open('hdmi_fill_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> hdmi_fill_fw.bin')"

flash-hdmi-fill: hdmi_fill_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing hdmi_fill firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 hdmi_fill_fw.bin

# Combined target: flash minimal bitstream + HDMI FULL-FILL firmware (solid
# red across the whole 32x32 test region, not just one pixel -- confirms the
# whole scanout read path on a KNOWN pattern instead of uninitialized SDRAM).
minimal-hdmi-fill: borg-minimal.bit hdmi_fill_fw.bin
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing minimal ECP5 bitstream ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash borg-minimal.bit
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing hdmi_fill firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 hdmi_fill_fw.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Power-cycle, connect HDMI. Expect: SOLID red 32x32 overlay. Run 'make tio' for 'R' confirmation."

hdmi_halfsplit_fw.bin: hdmi_halfsplit_fw.s
	@echo -e "$(BOLD)[FW]$(RESET) Assembling hdmi_halfsplit_fw.s ..."
	riscv64-unknown-elf-as -march=rv32i -mabi=ilp32 -o hdmi_halfsplit_fw.o hdmi_halfsplit_fw.s
	riscv64-unknown-elf-ld -melf32lriscv -Ttext=0x0 -o hdmi_halfsplit_fw.elf hdmi_halfsplit_fw.o
	riscv64-unknown-elf-objcopy -O binary hdmi_halfsplit_fw.elf hdmi_halfsplit_fw_raw.bin
	@python3 -c "import struct; d=open('hdmi_halfsplit_fw_raw.bin','rb').read(); open('hdmi_halfsplit_fw.bin','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> hdmi_halfsplit_fw.bin')"

# Firmware-only target -- reuses whatever borg-minimal.bit is already
# flashed (no bitstream rebuild). Exercises the tiled addressing math
# (tileIndex/pixIndex), not just a uniform readback.
flash-hdmi-halfsplit: hdmi_halfsplit_fw.bin
	@echo -e "$(BOLD)[FLASH-FW]$(RESET) Flashing hdmi_halfsplit firmware @ 0x400000 ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 hdmi_halfsplit_fw.bin
	@echo -e "$(BOLD)[DONE]$(RESET) Reset the board. Expect: top half red, bottom half blue."

######### Minimal SoC, RV64 + CLINT (Hutt without Borg, Linux boot isolation) #######################

ULX3S_MINIMAL_LINUX_VERILOG_DIR = ../../out/ulx3s_minimal_linux/verilog
MIN_LINUX_SRC_FILES = $$(find $(ULX3S_MINIMAL_LINUX_VERILOG_DIR) -maxdepth 1 -name '*.sv' | tr '\n' ' ')
MIN_LINUX_LPF = $(ULX3S_MINIMAL_LINUX_VERILOG_DIR)/ulx3s.lpf

.verilog_stamp_ulx3s_minimal_linux: $(CHISEL_SRCS) Makefile ../../Makefile
	$(MAKE) -C ../.. generate_verilog_ulx3s_minimal_linux
	@touch $@

borg-minimal-linux.json: .verilog_stamp_ulx3s_minimal_linux
	@echo -e "$(BOLD)[SYNTH]$(RESET) Running Yosys for minimal RV64 SoC ..."
	yosys -p "read -sv $(MIN_LINUX_SRC_FILES); \
	           read_verilog -sv -lib $(CURDIR)/primitives/ecp5_prims.v; \
	           synth_ecp5 -abc9 -top ulx3s_minimal_top -json borg-minimal-linux.json; \
	           stat -top ulx3s_minimal_top" \
	      -DECP5 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_ \
	      > yosys-minimal-linux.log
	@echo -e "$(BOLD)[SYNTH]$(RESET) Done."

borg-minimal-linux.config: borg-minimal-linux.json
	@echo -e "$(BOLD)[PNR]$(RESET) Running nextpnr-ecp5 for minimal RV64 SoC ..."
	nextpnr-ecp5 \
	  --85k --package CABGA381 \
	  --json borg-minimal-linux.json \
	  --lpf $(MIN_LINUX_LPF) \
	  --textcfg borg-minimal-linux.config \
	  --freq $(CLOCK_FREQ) \
	  --timing-allow-fail \
	  --log nextpnr-minimal-linux.log \
	  --seed 0
	@echo -e "$(BOLD)[PNR]$(RESET) Done."

borg-minimal-linux.bit: borg-minimal-linux.config
	@echo -e "$(BOLD)[PACK]$(RESET) Packing minimal RV64 bitstream ..."
	ecppack --svf borg-minimal-linux.svf borg-minimal-linux.config borg-minimal-linux.bit
	@echo -e "$(BOLD)[PACK]$(RESET) Done — borg-minimal-linux.bit ready."

# Flash minimal RV64 bitstream only (does NOT reset — flash firmware next, or
# 'make reset' to boot with whatever firmware is already at 0x400000).
flash-minimal-linux: borg-minimal-linux.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing minimal RV64 bitstream to onboard flash ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash --skip-reset borg-minimal-linux.bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Bitstream flashed. Flash firmware @ 0x400000, then: make reset"

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
	rm -f borg-loopback.bit borg-loopback.config borg-loopback.json borg-loopback.svf \
	      yosys-loopback.log nextpnr-loopback.log .verilog_stamp_ulx3s_loopback
	rm -f borg-external.bit borg-external.config borg-external.json borg-external.svf \
	      yosys-external.log nextpnr-external.log .verilog_stamp_ulx3s_external
	rm -f borg-padloop.bit borg-padloop.config borg-padloop.json borg-padloop.svf \
	      yosys-padloop.log nextpnr-padloop.log .verilog_stamp_ulx3s_padloop

.PHONY: all help load flash flash-firmware minimal-boot \
        print_resources generate_verilog_ulx3s tio install-udev clean \
        flash-hdmi-tiled minimal-hdmi flash-hdmi-fill minimal-hdmi-fill \
        flash-hdmi-halfsplit \
        generate_verilog_ulx3s_loopback flash-loopback \
        generate_verilog_ulx3s_external
