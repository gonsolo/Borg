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

CHISEL_SRCS  := $(shell find soc/src $(ROOT)/hardware/soc/src -name '*.scala')

HDMI_VERILOG := $(ROOT)/out/nexysvideo_hdmi/verilog
HDMI_BIT     := $(BUILD_DIR)/hdmi/HdmiTop.bit

help:
	@echo "make hdmi          - Chisel -> Verilog -> bitstream (HDMI colour bars)"
	@echo "make program-hdmi  - build if needed, then load onto the board"
	@echo "make clean         - delete build outputs"

# Regenerate Verilog only when a Chisel file has changed.
.verilog_stamp_hdmi: $(CHISEL_SRCS)
	cd $(ROOT) && mill fpga.nexysvideo.soc.runMain nexysvideo.HdmiTopMain
	touch $@

hdmi: $(HDMI_BIT)

$(HDMI_BIT): .verilog_stamp_hdmi build.tcl NexysVideo.xdc
	mkdir -p $(BUILD_DIR)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log hdmi.log \
	    -source $(call host_path,build.tcl) \
	    -tclargs HdmiTop $(call host_path,$(HDMI_VERILOG)) $(call host_path,NexysVideo.xdc) hdmi

program-hdmi: $(HDMI_BIT)
	cd $(BUILD_DIR) && $(VIVADO) -mode batch -nojournal -log program.log \
	    -source $(call host_path,program.tcl) -tclargs hdmi/HdmiTop.bit

clean:
	rm -rf $(BUILD_DIR) .verilog_stamp_*

.PHONY: help hdmi program-hdmi clean