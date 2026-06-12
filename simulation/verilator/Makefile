ROOT      := $(shell git rev-parse --show-toplevel)
RDL_C_OUT ?= $(ROOT)/out/hardware/borg/rdl
HW_DIR    ?= $(ROOT)/out/hardware
FIRMWARE_DIR ?= $(ROOT)/software/borg

# Lazily evaluate SRC_FILES, avoiding failures if sim_files.txt doesn't exist yet
# Use `wildcard` to trigger rebuilds if ANY verilog file changes in the output dir
V_SRCS = $(wildcard $(HW_DIR)/borg/verilog_sim/*.sv)

all: triangle vkcube

$(HW_DIR)/borg/verilog_sim/sim_files.txt:
	$(MAKE) -C $(ROOT) generate_verilog_sim

# Verilator simulator depends on hardware generation and the actual verilog files.
# Uses the flat-MemBackendIO top (BorgSimTop) — no QSPI protocol; the C++ harness
# models a flat memory directly on the backend bus.
obj_dir/verilator_sim: main.cpp $(HW_DIR)/borg/verilog_sim/sim_files.txt $(V_SRCS)
	@rm -rf obj_dir
	@SRC_FILES=$$(cat $(HW_DIR)/borg/verilog_sim/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator models..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	-MAKEFLAGS "-s" \
	-I$(HW_DIR)/borg/verilog_sim \
	-I$(HW_DIR)/borg/verilog_sim/verification/assert -I$(HW_DIR)/borg/verilog_sim/verification \
	--top-module BorgSimTop \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	$(wildcard $(HW_DIR)/borg/verilog_sim/verification/layers-*.sv) \
	main.cpp \
	../common/BorgSimulatorBase.cpp \
	../common/texture_loader.cpp \
	../common/uart_decoder.cpp \
	-o verilator_sim

# Software dependency (implicitly requires hardware mmio mappings)
$(FIRMWARE_DIR)/%.bin: $(HW_DIR)/borg/verilog_sim/sim_files.txt
	$(MAKE) -C $(FIRMWARE_DIR) $*

# Simulation targets
triangle: obj_dir/verilator_sim $(FIRMWARE_DIR)/triangle.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/triangle.bin triangle 32 32 32

vkcube: obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube 32 32 64

vkcube_gui: python_extension $(FIRMWARE_DIR)/vkcube.bin
	python3 viewer.py

python_extension: obj_dir/verilator_sim
	@mkdir -p build && cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$$(which python3) -DVERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator -DCMAKE_EXPORT_COMPILE_COMMANDS=ON > /dev/null && $(MAKE) -s
	@ln -sf build/compile_commands.json compile_commands.json

# ── Full-SoC HDMI sim: renders THROUGH the scanout and captures the displayed
# ── frame (what the monitor shows) instead of reading SDRAM back directly. ──
HDMI_DIR  = $(HW_DIR)/borg/verilog_hdmi_sim
HDMI_SRCS = $(wildcard $(HDMI_DIR)/*.sv)
APP  ?= vkcube
FBW  ?= 128
FBH  ?= 128
TEXD ?= 64

$(HDMI_DIR)/sim_files.txt:
	cd $(ROOT) && CLOCK_MHZ=25 FB_W=$(FBW) FB_H=$(FBH) mill fpga.ulx3s.soc.runMain soc.BorgHdmiSimMain

obj_dir_hdmi/verilator_hdmi: main_hdmi.cpp BorgHdmiSimulator.h $(HDMI_DIR)/sim_files.txt $(HDMI_SRCS)
	@rm -rf obj_dir_hdmi
	@SRC_FILES=$$(cat $(HDMI_DIR)/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building HDMI Verilator model..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	-MAKEFLAGS "-s" --Mdir obj_dir_hdmi \
	-I$(HDMI_DIR) \
	-I$(HDMI_DIR)/verification/assert -I$(HDMI_DIR)/verification \
	--top-module BorgHdmiSimTop \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	$(wildcard $(HDMI_DIR)/verification/layers-*.sv) \
	main_hdmi.cpp \
	../common/BorgSimulatorBase.cpp \
	../common/texture_loader.cpp \
	../common/uart_decoder.cpp \
	-o verilator_hdmi

# make hdmi APP=triangle FBW=32 FBH=32 TEXD=32   (fast)
# make hdmi APP=vkcube   FBW=128 FBH=128 TEXD=64 (full)
hdmi: obj_dir_hdmi/verilator_hdmi $(FIRMWARE_DIR)/$(APP).bin
	./obj_dir_hdmi/verilator_hdmi $(FIRMWARE_DIR)/$(APP).bin $(APP) $(FBW) $(FBH) $(TEXD)

.PHONY: all triangle vkcube vkcube_gui hdmi clean

clean:
	rm -rf obj_dir obj_dir_hdmi build verilator_sim verilator_hdmi *.ppm *.so

