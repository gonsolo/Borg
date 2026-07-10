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

.PHONY: all vkcube_gui hdmi clean cts-uart cts-uart-golden

# The old triangle/vkcube app-config render targets are gone — content is no
# longer baked into firmware (see borg_kernel.c); it arrives from borgvk at
# runtime, driving the unmodified Vulkan-Tools cube.c.  cts-uart replays a
# captured borgvk UART burst and is the render smoke test now.
all: cts-uart

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
	$(wildcard $(2)/verification/*.sv) \
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

# Borg-free MinimalSoC (RV64) — real OpenSBI/Linux firmware preloaded via the
# dbg_* backdoor, watches the debug console UART (uo_out bit 6) for boot
# output. Isolates whether a silent real-hardware boot is a MinimalSoC-
# specific wiring bug vs. a genuine CPU/timing issue: see
# hardware/soc/src/MinimalSocSimTop.scala.
MINLINUX_DIR = $(ROOT)/out/hardware/soc/verilog_sim
MINLINUX_SRCS = $(wildcard $(MINLINUX_DIR)/*.sv)

.PHONY: minimal-linux-sim
minimal-linux-sim: obj_dir_minlinux/minimal_linux_sim

$(MINLINUX_DIR)/sim_files.txt:
	cd $(ROOT) && CLOCK_MHZ=25 mill hardware.soc.runMain soc.MinimalSocSimMain

obj_dir_minlinux/minimal_linux_sim: main_minimal_linux.cpp $(MINLINUX_DIR)/sim_files.txt $(MINLINUX_SRCS)
	@rm -rf obj_dir_minlinux
	@SRC_FILES=$$(cat $(MINLINUX_DIR)/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator model [MinimalSocSimTop]..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	--trace-fst --trace-structs --savable \
	-MAKEFLAGS "-s" --Mdir obj_dir_minlinux \
	-I$(MINLINUX_DIR) \
	--top-module MinimalSocSimTop \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	main_minimal_linux.cpp \
	../common/uart_decoder.cpp \
	-o minimal_linux_sim

# Same test, but through the REAL SdramBackend/SdramController/SdramChipModel
# JEDEC-protocol co-sim instead of the idealized SdramBackendSim — see
# hardware/soc/src/MinimalSocRealSdramSimTop.scala.
MINLINUX_REALSDRAM_DIR = $(ROOT)/out/hardware/soc/verilog_realsdram_sim
MINLINUX_REALSDRAM_SRCS = $(wildcard $(MINLINUX_REALSDRAM_DIR)/*.sv)

.PHONY: minimal-linux-realsdram-sim
minimal-linux-realsdram-sim: obj_dir_minlinux_realsdram/minimal_linux_realsdram_sim

$(MINLINUX_REALSDRAM_DIR)/sim_files.txt:
	cd $(ROOT) && CLOCK_MHZ=25 mill hardware.soc.runMain soc.MinimalSocRealSdramSimMain

obj_dir_minlinux_realsdram/minimal_linux_realsdram_sim: main_minimal_linux_realsdram.cpp $(MINLINUX_REALSDRAM_DIR)/sim_files.txt $(MINLINUX_REALSDRAM_SRCS)
	@rm -rf obj_dir_minlinux_realsdram
	@SRC_FILES=$$(cat $(MINLINUX_REALSDRAM_DIR)/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator model [MinimalSocRealSdramSimTop]..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	--trace-fst --trace-structs --savable \
	-MAKEFLAGS "-s" --Mdir obj_dir_minlinux_realsdram \
	-I$(MINLINUX_REALSDRAM_DIR) \
	--top-module MinimalSocRealSdramSimTop \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	main_minimal_linux_realsdram.cpp \
	../common/uart_decoder.cpp \
	-o minimal_linux_realsdram_sim

# Firmware binaries — always invoke the firmware Makefile so its cflags_stamp
# check detects flag changes (e.g. TARGET_ULX3S from a board build) that a
# timestamp check on $(FW_SRCS) alone would miss.
# CLOCK_MHZ=25 (matching ULX3S) — the borgvk UART drain loop's software
# polling needs the cycles/bit margin that gives; see borg_kernel.c and
# simulation/common/uart_tx.h (UartTx::set_cycles_per_bit, matched to 217 in
# main.cpp/viewer.py). CLOCK_MHZ=4 does not leave enough slack and drops bytes.
# NOTE: these must be explicit rules, not a %.bin pattern rule — GNU Make
# never applies an implicit/pattern rule to a target listed in .PHONY (it
# silently reports "nothing to be done" instead), so on a fresh checkout
# with no pre-existing kernel.bin the recipe below would never run.
.PHONY: $(FIRMWARE_DIR)/kernel.bin $(FIRMWARE_DIR)/textest.bin
$(FIRMWARE_DIR)/kernel.bin:
	$(MAKE) -C $(FIRMWARE_DIR) CLOCK_MHZ=25 kernel

$(FIRMWARE_DIR)/textest.bin:
	$(MAKE) -C $(FIRMWARE_DIR) CLOCK_MHZ=25 textest

# Simulation targets
kernel: obj_dir/verilator_sim $(FIRMWARE_DIR)/kernel.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/kernel.bin kernel 32 32

vkcube_gui: python_extension $(FIRMWARE_DIR)/kernel.bin
	python3 viewer.py

# make hdmi APP=kernel FBW=32 FBH=32
hdmi: obj_dir_hdmi/verilator_hdmi $(FIRMWARE_DIR)/$(APP).bin
	./obj_dir_hdmi/verilator_hdmi $(FIRMWARE_DIR)/$(APP).bin $(APP) $(FBW) $(FBH) $(TEXD)

# CTS UART path: replay a captured borgvk burst (geometry+shaders+texture+MVP)
# → firmware → RGB565 framebuffer.  kernel.bin bakes in no content, so a bare
# MVP packet renders nothing — see scripts/cts_uart_render.py.
cts-uart: obj_dir/verilator_sim $(FIRMWARE_DIR)/kernel.bin
	python3 '$(ROOT)/scripts/cts_uart_render.py' ./obj_dir/verilator_sim '$(FIRMWARE_DIR)/kernel.bin' kernel_cts_uart_00.ppm 128 128 --capture '$(ROOT)/simulation/golden/borgvk_capture.bin'

cts-uart-golden: obj_dir/verilator_sim $(FIRMWARE_DIR)/kernel.bin
	python3 '$(ROOT)/scripts/cts_uart_render.py' ./obj_dir/verilator_sim '$(FIRMWARE_DIR)/kernel.bin' '$(ROOT)/simulation/golden/vkcube_cts_uart_00.ppm' 128 128 --capture '$(ROOT)/simulation/golden/borgvk_capture.bin'

python_extension: obj_dir/verilator_sim
	@mkdir -p build && cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$$(which python3) -DVERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator -DCMAKE_EXPORT_COMPILE_COMMANDS=ON > /dev/null && $(MAKE) -s
	@ln -sf build/compile_commands.json compile_commands.json

clean:
	rm -rf obj_dir obj_dir_hdmi build verilator_sim verilator_hdmi *.ppm *.so
