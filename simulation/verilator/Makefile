include ../common.mk

HW_DIR    ?= $(ROOT)/out/hardware
HDMI_DIR   = $(HW_DIR)/borg/verilog_hdmi_sim

# Lazily evaluated: wildcard triggers rebuilds if any verilog file changes.
V_SRCS      = $(wildcard $(HW_DIR)/borg/verilog_sim/*.sv)
HDMI_SRCS   = $(wildcard $(HDMI_DIR)/*.sv)

APP  ?= vkcube
FBW  ?= 128
FBH  ?= 128
TEXD ?= 64

.PHONY: all triangle vkcube vkcube_gui hdmi clean cts-uart cts-uart-golden

all: triangle vkcube

# Hardware generation
$(HW_DIR)/borg/verilog_sim/sim_files.txt:
	$(MAKE) -C $(ROOT) generate_verilog_sim

$(HDMI_DIR)/sim_files.txt:
	cd $(ROOT) && CLOCK_MHZ=25 FB_W=$(FBW) FB_H=$(FBH) mill fpga.ulx3s.soc.runMain soc.BorgHdmiSimMain

# Common verilator invocation — avoids repeating 18 flags for the HDMI variant.
# $(1)=output-dir  $(2)=sv-dir  $(3)=top-module  $(4)=main-cpp  $(5)=binary-name
define VERILATE
	@rm -rf $(1)
	@SRC_FILES=$$(cat $(2)/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator model [$(3)]..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	-MAKEFLAGS "-s" --Mdir $(1) \
	-I$(2) -I$(2)/verification/assert -I$(2)/verification \
	--top-module $(3) \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	$$(wildcard $(2)/verification/layers-*.sv) \
	$(4) \
	../common/BorgSimulatorBase.cpp \
	../common/texture_loader.cpp \
	../common/uart_decoder.cpp \
	-o $(5)
endef

obj_dir/verilator_sim: main.cpp $(HW_DIR)/borg/verilog_sim/sim_files.txt $(V_SRCS)
	$(call VERILATE,obj_dir,$(HW_DIR)/borg/verilog_sim,BorgSimTop,main.cpp,verilator_sim)

obj_dir_hdmi/verilator_hdmi: main_hdmi.cpp $(HDMI_DIR)/sim_files.txt $(HDMI_SRCS)
	$(call VERILATE,obj_dir_hdmi,$(HDMI_DIR),BorgHdmiSimTop,main_hdmi.cpp,verilator_hdmi)

# Firmware binaries — always invoke the firmware Makefile so its cflags_stamp
# check detects flag changes (e.g. TARGET_ULX3S from a board build) that a
# timestamp check on $(FW_SRCS) alone would miss.
.PHONY: $(FIRMWARE_DIR)/triangle.bin $(FIRMWARE_DIR)/vkcube.bin $(FIRMWARE_DIR)/textest.bin
$(FIRMWARE_DIR)/%.bin:
	$(MAKE) -C $(FIRMWARE_DIR) $*

# Simulation targets
triangle: obj_dir/verilator_sim $(FIRMWARE_DIR)/triangle.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/triangle.bin triangle 32 32

vkcube: obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube 32 32

vkcube_gui: python_extension $(FIRMWARE_DIR)/vkcube.bin
	python3 viewer.py

# make hdmi APP=triangle FBW=32 FBH=32 TEXD=32   (fast)
# make hdmi APP=vkcube   FBW=128 FBH=128 TEXD=64  (full)
hdmi: obj_dir_hdmi/verilator_hdmi $(FIRMWARE_DIR)/$(APP).bin
	./obj_dir_hdmi/verilator_hdmi $(FIRMWARE_DIR)/$(APP).bin $(APP) $(FBW) $(FBH) $(TEXD)

# CTS UART path: borgvk-style 0xAD MVP packet → firmware → RGB565 framebuffer
cts-uart: obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin
	python3 '$(ROOT)/scripts/cts_uart_render.py' ./obj_dir/verilator_sim '$(FIRMWARE_DIR)/vkcube.bin' vkcube_cts_uart_00.ppm 128 128

cts-uart-golden: obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin
	python3 '$(ROOT)/scripts/cts_uart_render.py' ./obj_dir/verilator_sim '$(FIRMWARE_DIR)/vkcube.bin' '$(ROOT)/simulation/golden/vkcube_cts_uart_00.ppm' 128 128

python_extension: obj_dir/verilator_sim
	@mkdir -p build && cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$$(which python3) -DVERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator -DCMAKE_EXPORT_COMPILE_COMMANDS=ON > /dev/null && $(MAKE) -s
	@ln -sf build/compile_commands.json compile_commands.json

clean:
	rm -rf obj_dir obj_dir_hdmi build verilator_sim verilator_hdmi *.ppm *.so
