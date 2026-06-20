include ../common.mk

ARCILATOR = arcilator
FIRTOOL   = firtool
LLC       = llc

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

.PHONY: all clean triangle vkcube vkcube_gui textest

all: triangle vkcube

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

# Firmware binaries — rebuild when firmware C/H/ASM sources change.
$(FIRMWARE_DIR)/%.bin: $(FW_SRCS)
	$(MAKE) -C $(FIRMWARE_DIR) $*

triangle: arcilator_sim $(FIRMWARE_DIR)/triangle.bin
	./arcilator_sim $(FIRMWARE_DIR)/triangle.bin triangle

vkcube: arcilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./arcilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube

vkcube_gui: python_extension $(FIRMWARE_DIR)/vkcube.bin
	python3 arc_viewer.py

textest: arcilator_sim $(FIRMWARE_DIR)/textest.bin
	./arcilator_sim $(FIRMWARE_DIR)/textest.bin textest

clean:
	rm -f arc.h arc.ll borg.mlir state.json *.o arcilator_sim *.ppm
	rm -rf build *.so

python_extension: arcilator_sim
	@$(MAKE) -C $(ROOT)/software/borg/python
	mkdir -p build && cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$(shell which python3) -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && $(MAKE)
	cp build/*.so .
