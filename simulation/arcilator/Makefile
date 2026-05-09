ARCILATOR = arcilator
FIRTOOL = firtool
LLC = llc
CLANG = clang++
CIRCT_TOOLS = $(shell dirname $(shell which arcilator))

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

ROOT      := $(shell git rev-parse --show-toplevel)
RDL_C_OUT ?= $(ROOT)/out/hardware/borg/rdl
FIRMWARE_DIR ?= $(ROOT)/software/borg

# Track Chisel sources so the .fir is regenerated when hardware changes
CHISEL_SRCS = $(shell find $(ROOT)/hardware/borg/src $(ROOT)/hardware/soc/src \
                           $(ROOT)/hardware/tinyqv/src $(ROOT)/hardware/memory/src \
                           $(ROOT)/asic/tt/src \
                           -name '*.scala' -not -path '*/generated/*' 2>/dev/null) \
              $(wildcard $(ROOT)/hardware/rdl/*.rdl)

.PHONY: all clean triangle vkcube $(FIRMWARE_DIR)/%.bin

all: triangle vkcube

# Step 0: Hardware Generation — reruns when any Chisel/RDL source changes
$(ROOT)/out/hardware/borg/firrtl/tt_um_gonsolo_borg.fir: $(CHISEL_SRCS)
	$(MAKE) -C $(ROOT) generate_verilog

# Step 1: FIRRTL → HW MLIR
borg.mlir: $(ROOT)/out/hardware/borg/firrtl/tt_um_gonsolo_borg.fir
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

# Step 5: Build test harness
texture_loader.o: ../common/texture_loader.cpp ../common/texture_loader.h
	$(CLANG) -O3 -c ../common/texture_loader.cpp -I../common -I$(RDL_C_OUT) -o $@

uart_decoder.o: ../common/uart_decoder.cpp ../common/uart_decoder.h
	$(CLANG) -O3 -c ../common/uart_decoder.cpp -I../common -o $@

BorgSimulatorBase.o: ../common/BorgSimulatorBase.cpp ../common/BorgSimulatorBase.h
	$(CLANG) -O3 -c ../common/BorgSimulatorBase.cpp -I../common -o $@

ArcBorgSimulator.o: ArcBorgSimulator.cpp ArcBorgSimulator.h
	$(CLANG) -O3 -c ArcBorgSimulator.cpp -I../common -o $@

main.o: main.cpp arc.h arcilator-runtime.h ../common/texture_loader.h ArcBorgSimulator.h ../common/BorgSimulatorBase.h
	$(CLANG) -O3 -c main.cpp -I../common -o $@

# Step 6: Link
arcilator_sim: main.o arc.o texture_loader.o uart_decoder.o BorgSimulatorBase.o ArcBorgSimulator.o
	$(CLANG) -O3 main.o arc.o texture_loader.o uart_decoder.o BorgSimulatorBase.o ArcBorgSimulator.o -o $@

# Dependency on firmware binaries
$(FIRMWARE_DIR)/%.bin:
	$(MAKE) -C $(FIRMWARE_DIR) $*

triangle: arcilator_sim $(FIRMWARE_DIR)/triangle.bin
	./arcilator_sim $(FIRMWARE_DIR)/triangle.bin triangle

vkcube: arcilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./arcilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube

textest: arcilator_sim $(FIRMWARE_DIR)/textest.bin
	./arcilator_sim $(FIRMWARE_DIR)/textest.bin textest

clean:
	rm -f arc.h arc.ll borg.mlir state.json *.o arcilator_sim *.ppm
	rm -rf build *.so

python_extension: arcilator_sim
	@$(MAKE) -C $(ROOT)/software/borg/python
	mkdir -p build && cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$(shell which python3) -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && $(MAKE)
	cp build/*.so .
