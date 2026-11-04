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

CHISEL_SRCS  := $(shell find soc/src $(ROOT)/hardware/soc/src $(ROOT)/hardware/memory/src $(ROOT)/fpga/ulx3s/soc/src -name '*.scala')

HDMI_VERILOG := $(ROOT)/out/nexysvideo_hdmi/verilog
HDMI_BIT     := $(BUILD_DIR)/hdmi/HdmiTop.bit

DDR3_VERILOG := $(ROOT)/out/nexysvideo_ddr3/verilog
DDR3_BIT     := $(BUILD_DIR)/ddr3/DDR3Top.bit
MIG_IP       := ip/mig_7series_0

MINIMAL_VERILOG := $(ROOT)/out/nexysvideo_minimal/verilog
MINIMAL_BIT     := $(BUILD_DIR)/minimal/nexysvideo_minimal_top.bit

# Where the bootloader looks for the program in flash
FLASH_OFFSET := 0x00400000

help:
	@echo "make hdmi             - Chisel -> Verilog -> bitstream (HDMI colour bars)"
	@echo "make program-hdmi     - build if needed, then load onto the board"
	@echo "make ddr3             - Chisel -> Verilog -> bitstream (DDR3 write/read-back test)"
	@echo "make program-ddr3     - build if needed, then load onto the board"
	@echo "make minimal          - Chisel -> Verilog -> bitstream (minimal SoC: CPU + UART + HDMI on DDR3)"
	@echo "make program-minimal  - build if needed, then load onto the board"
	@echo "make flash-slow-test  - write a 1 MB test file into flash (bootloader takes ~5-10 s to copy it)"
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

program-hdmi: $(HDMI_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs hdmi/HdmiTop.bit

program-ddr3: $(DDR3_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs ddr3/DDR3Top.bit

program-minimal: $(MINIMAL_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs minimal/nexysvideo_minimal_top.bit

# Flash test file: 4-byte size header + 1 MB ("jump to itself" instruction, then zeros).
# Copying 1 MB takes ~5-10 s, so LD6 (bootDone) comes on late only if flash reads work.
$(BUILD_DIR)/slow_test.bin:
	mkdir -p $(BUILD_DIR)
	python3 -c "import struct; fw=bytes([0x6f,0,0,0])+bytes(0x100000-4); open('$@','wb').write(struct.pack('<I',len(fw))+fw)"

flash-slow-test: $(BUILD_DIR)/slow_test.bin flash.tcl
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log flash.log \
	    -source $(call host_path,flash.tcl) -tclargs slow_test.bin $(FLASH_OFFSET)

clean:
	rm -rf $(BUILD_DIR) .verilog_stamp_*

.PHONY: help hdmi program-hdmi ddr3 program-ddr3 minimal program-minimal flash-slow-test clean