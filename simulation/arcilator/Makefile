ARCILATOR = arcilator
FIRTOOL = firtool
LLC = llc
CLANG = clang++
CIRCT_TOOLS = $(shell dirname $(shell which arcilator))

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

FIRMWARE_DIR = ../../software/borg

# Track Chisel sources so the .fir is regenerated when hardware changes
CHISEL_SRCS = $(shell find ../../hardware/borg/src ../../hardware/soc/src \
                           ../../hardware/tinyqv/src ../../hardware/memory/src \
                           -name '*.scala' -not -path '*/generated/*' 2>/dev/null) \
              $(wildcard ../../hardware/rdl/*.rdl)

.PHONY: all clean triangle vkcube $(FIRMWARE_DIR)/%.bin

all: triangle vkcube

# Step 0: Hardware Generation — reruns when any Chisel/RDL source changes
# Step 0: Hardware Generation — reruns when any Chisel/RDL source changes
# DEBUG: Using sim FIRRTL to test GPU read bypass via MemoryControllerSim
../../out/hardware/borg/firrtl_sim/tt_um_gonsolo_borg.fir: $(CHISEL_SRCS)
	$(MAKE) -C ../../ generate_verilog

# Step 1: FIRRTL → HW MLIR
borg.mlir: ../../out/hardware/borg/firrtl_sim/tt_um_gonsolo_borg.fir
	$(FIRTOOL) $< --disable-layers=Verification --ir-hw > $@

# Step 2: HW MLIR → LLVM IR + state info
arc.ll state.json: borg.mlir
	$(ARCILATOR) $< --inline --observe-memories --state-file state.json -o arc.ll

# Step 3: LLVM IR → object
arc.o: arc.ll
	$(LLC) -O3 -relocation-model=pic --filetype=obj $< -o $@

# Step 4: state.json → C++ header
arc.h: state.json
	python3 arcilator-header-cpp.py $< > $@

# Step 5: Build test harness
texture_loader.o: ../common/texture_loader.cpp ../common/texture_loader.h
	$(CLANG) -O3 -c ../common/texture_loader.cpp -I../common -I../../out/hardware/borg/rdl -o $@

uart_decoder.o: ../common/uart_decoder.cpp ../common/uart_decoder.h
	$(CLANG) -O3 -c ../common/uart_decoder.cpp -I../common -o $@

BorgSimulatorBase.o: ../common/BorgSimulatorBase.cpp ../common/BorgSimulatorBase.h
	$(CLANG) -O3 -c ../common/BorgSimulatorBase.cpp -I../common -o $@

ArcBorgSimulator.o: ArcBorgSimulator.cpp ArcBorgSimulator.h
	$(CLANG) -O3 -c ArcBorgSimulator.cpp -I../common -o $@

main.o: main.cpp arc.h arcilator-runtime.h ../common/texture_loader.h
	$(CLANG) -O3 -c main.cpp -I../common -o $@

# Step 6: Link
arcilator_sim: main.o arc.o texture_loader.o uart_decoder.o BorgSimulatorBase.o ArcBorgSimulator.o
	$(CLANG) -O3 main.o arc.o texture_loader.o uart_decoder.o BorgSimulatorBase.o ArcBorgSimulator.o -o $@

# Dependency on firmware binaries
$(FIRMWARE_DIR)/%.bin:
	$(MAKE) -C $(FIRMWARE_DIR) $*

triangle: arcilator_sim $(FIRMWARE_DIR)/triangle.bin
	./arcilator_sim $(FIRMWARE_DIR)/triangle.bin triangle fast

vkcube: arcilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./arcilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube fast

triangle_slow: arcilator_sim $(FIRMWARE_DIR)/triangle.bin
	./arcilator_sim $(FIRMWARE_DIR)/triangle.bin triangle slow

vkcube_slow: arcilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./arcilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube slow

clean:
	rm -f arc.h arc.ll borg.mlir state.json *.o arcilator_sim *.ppm
