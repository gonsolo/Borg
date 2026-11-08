# SPDX-FileCopyrightText: © 2026 Ziad Mubarak
# Build and program the Nexys Video designs with Vivado (non-project mode).
# Run from fpga/nexysvideo inside `nix develop`.

ROOT      := ../..
VIVADO    ?= vivado
BUILD_DIR ?= build

# Machine-specific settings (Vivado path, build folder). Not in git.
-include local.mk

# Vivado needs paths it can open: Windows paths on WSL, normal paths on Linux.
ifneq ($(shell command -v wslpath),)
  host_path = $(shell wslpath -m $(abspath $(1)))
else
  host_path = $(abspath $(1))
endif

# All Chisel sources the designs depend on (board files, SoC, memory, CPU, Borg, scan-out)
CHISEL_SRCS  := $(shell find soc/src $(ROOT)/hardware $(ROOT)/fpga/ulx3s/soc/src -name '*.scala')

HDMI_VERILOG := $(ROOT)/out/nexysvideo_hdmi/verilog
HDMI_BIT     := $(BUILD_DIR)/hdmi/HdmiTop.bit

DDR3_VERILOG := $(ROOT)/out/nexysvideo_ddr3/verilog
DDR3_BIT     := $(BUILD_DIR)/ddr3/DDR3Top.bit
MIG_IP       := ip/mig_7series_0

MINIMAL_VERILOG := $(ROOT)/out/nexysvideo_minimal/verilog
MINIMAL_BIT     := $(BUILD_DIR)/minimal/nexysvideo_minimal_top.bit

BORG_VERILOG := $(ROOT)/out/nexysvideo/verilog
BORG_BIT     := $(BUILD_DIR)/borg/nexysvideo_top.bit

# Where the bootloader looks for the program in flash
FLASH_OFFSET := 0x00400000

# SoC clock in MHz: must match sysClock (Clocks) and nexysvideo_top's CLOCK_MHZ.
# The firmware uses it for timing and the UART baud rate.
SOC_MHZ := 25

# Andreas's Borg firmware (bare-metal C, runs on the CPU, receives drawing work over UART)
FIRMWARE_DIR := $(ROOT)/software/borg

# Andreas's test programs (run on the minimal SoC's CPU)
UART_HELLO_SRC := $(ROOT)/fpga/ulx3s/uart_hello.s
SDRAM_FB_SRC   := $(ROOT)/fpga/ulx3s/sdram_fb.s

# My UART check: message + SoC ID register + DDR3 write/read-back (runs on the minimal and full SoC)
UART_TEST_SRC  := uart_test.s

help:
	@echo "make hdmi             - Chisel -> Verilog -> bitstream (HDMI colour bars)"
	@echo "make program-hdmi     - build if needed, then load onto the board"
	@echo "make ddr3             - Chisel -> Verilog -> bitstream (DDR3 write/read-back test)"
	@echo "make program-ddr3     - build if needed, then load onto the board"
	@echo "make minimal          - Chisel -> Verilog -> bitstream (minimal SoC: CPU + UART + HDMI on DDR3)"
	@echo "make program-minimal  - build if needed, then load onto the board"
	@echo "make borg             - Chisel -> Verilog -> bitstream (full SoC: CPU + Borg GPU + UART + HDMI on DDR3)"
	@echo "make program-borg     - build if needed, then load onto the board"
	@echo "make flash-slow-test  - write a 1 MB test file into flash (bootloader takes ~5-10 s to copy it)"
	@echo "make flash-uart-hello - write Andreas's uart_hello program into flash (sends 'Z' over UART)"
	@echo "make flash-sdram-fb   - write Andreas's sdram_fb program into flash (red square on HDMI)"
	@echo "make flash-uart-test  - write the UART check into flash (message, ID 'A', DDR3 P/F)"
	@echo "make flash-firmware   - build Andreas's Borg firmware for $(SOC_MHZ) MHz and write it into flash"
	@echo "make flash-replay     - build Andreas's replay firmware (one captured vkcube frame, no host) and write it into flash"
	@echo "make clean            - delete build outputs"

# Regenerate Verilog only when a Chisel file has changed.
.verilog_stamp_hdmi: $(CHISEL_SRCS)
	cd $(ROOT) && mill fpga.nexysvideo.soc.runMain nexysvideo.HdmiTopMain
	touch $@

.verilog_stamp_ddr3: $(CHISEL_SRCS)
	cd $(ROOT) && mill fpga.nexysvideo.soc.runMain nexysvideo.DDR3TopMain
	touch $@

.verilog_stamp_minimal: $(CHISEL_SRCS)
	cd $(ROOT) && mill fpga.nexysvideo.soc.runMain nexysvideo.NexysVideoMinimalMain
	touch $@

.verilog_stamp_borg: $(CHISEL_SRCS)
	cd $(ROOT) && mill fpga.nexysvideo.soc.runMain nexysvideo.NexysVideoTopMain
	touch $@

hdmi: $(HDMI_BIT)

$(HDMI_BIT): .verilog_stamp_hdmi build.tcl NexysVideo.xdc
	mkdir -p $(BUILD_DIR)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log hdmi.log \
	    -source $(call host_path,build.tcl) \
	    -tclargs HdmiTop $(call host_path,$(HDMI_VERILOG)) $(call host_path,NexysVideo.xdc) hdmi

ddr3: $(DDR3_BIT)

$(DDR3_BIT): .verilog_stamp_ddr3 build.tcl DDR3Top.xdc $(wildcard $(MIG_IP)/*)
	mkdir -p $(BUILD_DIR)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log ddr3.log \
	    -source $(call host_path,build.tcl) \
	    -tclargs DDR3Top $(call host_path,$(DDR3_VERILOG)) $(call host_path,DDR3Top.xdc) ddr3 \
	    $(call host_path,$(MIG_IP))

minimal: $(MINIMAL_BIT)

$(MINIMAL_BIT): .verilog_stamp_minimal build.tcl NexysVideoMinimal.xdc $(wildcard $(MIG_IP)/*)
	mkdir -p $(BUILD_DIR)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log minimal.log \
	    -source $(call host_path,build.tcl) \
	    -tclargs nexysvideo_minimal_top $(call host_path,$(MINIMAL_VERILOG)) $(call host_path,NexysVideoMinimal.xdc) minimal \
	    $(call host_path,$(MIG_IP))

# Full SoC with the Borg GPU. Same pins as the minimal SoC, so it reuses its XDC.
borg: $(BORG_BIT)

$(BORG_BIT): .verilog_stamp_borg build.tcl NexysVideoMinimal.xdc $(wildcard $(MIG_IP)/*)
	mkdir -p $(BUILD_DIR)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log borg.log \
	    -source $(call host_path,build.tcl) \
	    -tclargs nexysvideo_top $(call host_path,$(BORG_VERILOG)) $(call host_path,NexysVideoMinimal.xdc) borg \
	    $(call host_path,$(MIG_IP))

program-hdmi: $(HDMI_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs hdmi/HdmiTop.bit

program-ddr3: $(DDR3_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs ddr3/DDR3Top.bit

program-minimal: $(MINIMAL_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs minimal/nexysvideo_minimal_top.bit

program-borg: $(BORG_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs borg/nexysvideo_top.bit

# Flash test file: 4-byte size header + 1 MB ("jump to itself" instruction, then zeros).
# Copying 1 MB takes ~5-10 s, so LD6 (bootDone) comes on late only if flash reads work.
$(BUILD_DIR)/slow_test.bin:
	mkdir -p $(BUILD_DIR)
	python3 -c "import struct; fw=bytes([0x6f,0,0,0])+bytes(0x100000-4); open('$@','wb').write(struct.pack('<I',len(fw))+fw)"

flash-slow-test: $(BUILD_DIR)/slow_test.bin flash.tcl
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log flash.log \
	    -source $(call host_path,flash.tcl) -tclargs slow_test.bin $(FLASH_OFFSET)

# UART test: Andreas's 4 build steps (assemble, link at address 0, raw bytes, size header),
# with this nix shell's riscv32-none-elf- tools.
$(BUILD_DIR)/uart_hello_fw.bin: $(UART_HELLO_SRC)
	mkdir -p $(BUILD_DIR)
	riscv32-none-elf-as -march=rv32i -mabi=ilp32 -o $(BUILD_DIR)/uart_hello.o $<
	riscv32-none-elf-ld -melf32lriscv -Ttext=0x0 -o $(BUILD_DIR)/uart_hello.elf $(BUILD_DIR)/uart_hello.o
	riscv32-none-elf-objcopy -O binary $(BUILD_DIR)/uart_hello.elf $(BUILD_DIR)/uart_hello_raw.bin
	python3 -c "import struct; d=open('$(BUILD_DIR)/uart_hello_raw.bin','rb').read(); open('$@','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header')"

flash-uart-hello: $(BUILD_DIR)/uart_hello_fw.bin flash.tcl
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log flash.log \
	    -source $(call host_path,flash.tcl) -tclargs uart_hello_fw.bin $(FLASH_OFFSET)

# HDMI picture test: Andreas's sdram_fb.s paints the framebuffer at 0x100000 red.
# Same 4 build steps as the UART test.
$(BUILD_DIR)/sdram_fb_fw.bin: $(SDRAM_FB_SRC)
	mkdir -p $(BUILD_DIR)
	riscv32-none-elf-as -march=rv32i -mabi=ilp32 -o $(BUILD_DIR)/sdram_fb.o $<
	riscv32-none-elf-ld -melf32lriscv -Ttext=0x0 -o $(BUILD_DIR)/sdram_fb.elf $(BUILD_DIR)/sdram_fb.o
	riscv32-none-elf-objcopy -O binary $(BUILD_DIR)/sdram_fb.elf $(BUILD_DIR)/sdram_fb_raw.bin
	python3 -c "import struct; d=open('$(BUILD_DIR)/sdram_fb_raw.bin','rb').read(); open('$@','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header')"

flash-sdram-fb: $(BUILD_DIR)/sdram_fb_fw.bin flash.tcl
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log flash.log \
	    -source $(call host_path,flash.tcl) -tclargs sdram_fb_fw.bin $(FLASH_OFFSET)

# UART check: same 4 build steps as the UART test.
$(BUILD_DIR)/uart_test_fw.bin: $(UART_TEST_SRC)
	mkdir -p $(BUILD_DIR)
	riscv32-none-elf-as -march=rv32i -mabi=ilp32 -o $(BUILD_DIR)/uart_test.o $<
	riscv32-none-elf-ld -melf32lriscv -Ttext=0x0 -o $(BUILD_DIR)/uart_test.elf $(BUILD_DIR)/uart_test.o
	riscv32-none-elf-objcopy -O binary $(BUILD_DIR)/uart_test.elf $(BUILD_DIR)/uart_test_raw.bin
	python3 -c "import struct; d=open('$(BUILD_DIR)/uart_test_raw.bin','rb').read(); open('$@','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header')"

flash-uart-test: $(BUILD_DIR)/uart_test_fw.bin flash.tcl
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log flash.log \
	    -source $(call host_path,flash.tcl) -tclargs uart_test_fw.bin $(FLASH_OFFSET)

# Borg firmware: built by Andreas's Makefile with his ULX3S settings (same address map:
# debug UART at 0x08000018, user UART for receiving, DRAM window at 16 MB) and our clock.
# Always handed to his Makefile, which only rebuilds what changed.
.PHONY: $(FIRMWARE_DIR)/kernel.bin
$(FIRMWARE_DIR)/kernel.bin:
	$(MAKE) -C $(FIRMWARE_DIR) CLOCK_MHZ=$(SOC_MHZ) EXTRA_CFLAGS="-DTARGET_ULX3S" kernel

# Add the 4-byte size header the bootloader reads first.
$(BUILD_DIR)/firmware_fw.bin: $(FIRMWARE_DIR)/kernel.bin
	mkdir -p $(BUILD_DIR)
	python3 -c "import struct; d=open('$<','rb').read(); open('$@','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header')"

flash-firmware: $(BUILD_DIR)/firmware_fw.bin flash.tcl
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log flash.log \
	    -source $(call host_path,flash.tcl) -tclargs firmware_fw.bin $(FLASH_OFFSET)

# Replay firmware: renders one captured vkcube frame with no host (borg_replay.c), shows it
# on HDMI and prints it over the UART between FRAME-BEGIN/FRAME-END. Compared with the
# simulation golden (simulation/golden/vkcube_cts_uart_00.ppm): the on-board golden-image test.
.PHONY: $(FIRMWARE_DIR)/replay.bin
$(FIRMWARE_DIR)/replay.bin:
	$(MAKE) -C $(FIRMWARE_DIR) CLOCK_MHZ=$(SOC_MHZ) EXTRA_CFLAGS="-DTARGET_ULX3S" replay

$(BUILD_DIR)/replay_fw.bin: $(FIRMWARE_DIR)/replay.bin
	mkdir -p $(BUILD_DIR)
	python3 -c "import struct; d=open('$<','rb').read(); open('$@','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header')"

flash-replay: $(BUILD_DIR)/replay_fw.bin flash.tcl
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log flash.log \
	    -source $(call host_path,flash.tcl) -tclargs replay_fw.bin $(FLASH_OFFSET)

clean:
	rm -rf $(BUILD_DIR) .verilog_stamp_*

.PHONY: help hdmi program-hdmi ddr3 program-ddr3 minimal program-minimal borg program-borg flash-slow-test flash-uart-hello flash-sdram-fb flash-uart-test flash-firmware flash-replay clean