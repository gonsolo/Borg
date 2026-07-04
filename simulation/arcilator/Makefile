include ../common.mk

ARCILATOR = arcilator
FIRTOOL   = firtool
LLC       = llc

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

.PHONY: all clean vkcube_gui textest cts-uart cts-uart-golden

# The old triangle/vkcube app-config render targets are gone — content is no
# longer baked into firmware (see borg_kernel.c); it arrives from borgvk at
# runtime, driving the unmodified Vulkan-Tools cube.c.  cts-uart replays a
# captured borgvk UART burst and is the render smoke test now.
all: cts-uart

# Step 0: Hardware Generation — reruns when any Chisel/RDL source changes
$(ROOT)/out/hardware/borg/firrtl_sim/BorgArcSimTop.fir: $(CHISEL_SRCS)
	$(MAKE) -C $(ROOT) generate_verilog_sim

# Step 1: FIRRTL → HW MLIR
borg.mlir: $(ROOT)/out/hardware/borg/firrtl_sim/BorgArcSimTop.fir
	$(FIRTOOL) $< --disable-layers=Verification --ir-hw > $@

# Step 2: HW MLIR → LLVM IR + state info  (single invocation produces both)
arc.ll state.json &: borg.mlir
	$(ARCILATOR) $< --inline --observe-memories --state-file state.json -o arc.ll

# Step 3: LLVM IR → object
arc.o: arc.ll
	$(LLC) -O3 -relocation-model=pic --filetype=obj $< -o $@

# Step 4: state.json → C++ header
arc.h: state.json
	python3 arcilator-header-cpp.py $< > $@

# Step 5: Build harness objects.
# Harness sources bake in the firmware memory map via common_sim.h →
# software/borg/borg_layout.h.  Wildcard firmware headers so a layout change
# triggers a rebuild (prevents stale-object render hangs).

texture_loader.o: ../common/texture_loader.cpp ../common/texture_loader.h $(LAYOUT_HDRS)
	$(CLANG) $(CFLAGS_COMMON) -c $< -o $@

uart_decoder.o: ../common/uart_decoder.cpp ../common/uart_decoder.h
	$(CLANG) -O3 -I../common -c $< -o $@

BorgSimulatorBase.o: ../common/BorgSimulatorBase.cpp ../common/BorgSimulatorBase.h $(LAYOUT_HDRS)
	$(CLANG) $(CFLAGS_COMMON) -c $< -o $@

# NOTE: arc.h is a generated dependency — it carries BorgArcSimTopLayout::
# numStateBytes, which the BorgArcSimTop ctor (inlined here via `new
# BorgArcSimTop`) uses to size the state buffer.  If arc.h regenerates with a
# larger state (e.g. after widening the CPU to RV64) but this object is NOT
# rebuilt, the ctor under-allocates while the freshly built arc.o writes the
# full state → heap overflow into the adjacent `flat` QSPIMemory → segfault.
ArcBorgSimulator.o: ArcBorgSimulator.cpp ArcBorgSimulator.h arc.h ../common/BorgSimulatorBase.h ../common/uart_tx.h $(LAYOUT_HDRS)
	$(CLANG) $(CFLAGS_COMMON) -c $< -o $@

main.o: main.cpp arc.h arcilator-runtime.h ../common/texture_loader.h ArcBorgSimulator.h ../common/BorgSimulatorBase.h $(LAYOUT_HDRS)
	$(CLANG) $(CFLAGS_COMMON) -c $< -o $@

# Step 6: Link
arcilator_sim: main.o arc.o texture_loader.o uart_decoder.o BorgSimulatorBase.o ArcBorgSimulator.o
	$(CLANG) -O3 $^ -o $@

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

kernel: arcilator_sim $(FIRMWARE_DIR)/kernel.bin
	./arcilator_sim $(FIRMWARE_DIR)/kernel.bin kernel

vkcube_gui: python_extension $(FIRMWARE_DIR)/kernel.bin
	python3 arc_viewer.py

textest: arcilator_sim $(FIRMWARE_DIR)/textest.bin
	./arcilator_sim $(FIRMWARE_DIR)/textest.bin textest

# CTS UART path: replay a captured borgvk burst (geometry+shaders+texture+MVP)
# → firmware → RGB565 framebuffer.  kernel.bin bakes in no content, so a bare
# MVP packet renders nothing — see scripts/cts_uart_render.py.
cts-uart: arcilator_sim $(FIRMWARE_DIR)/kernel.bin
	python3 '$(ROOT)/scripts/cts_uart_render.py' ./arcilator_sim '$(FIRMWARE_DIR)/kernel.bin' kernel_cts_uart_00.ppm 128 128 --capture '$(ROOT)/simulation/golden/borgvk_capture.bin'

cts-uart-golden: arcilator_sim $(FIRMWARE_DIR)/kernel.bin
	python3 '$(ROOT)/scripts/cts_uart_render.py' ./arcilator_sim '$(FIRMWARE_DIR)/kernel.bin' '$(ROOT)/simulation/golden/vkcube_cts_uart_00.ppm' 128 128 --capture '$(ROOT)/simulation/golden/borgvk_capture.bin'

clean:
	rm -f arc.h arc.ll borg.mlir state.json *.o arcilator_sim *.ppm
	rm -rf build *.so

python_extension: arcilator_sim
	@$(MAKE) -C $(ROOT)/software/borg/python
	mkdir -p build && cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$(shell which python3) -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && $(MAKE)
	cp build/*.so .
