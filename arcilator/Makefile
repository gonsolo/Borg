ARCILATOR = arcilator
FIRTOOL = firtool
LLC = llc
CLANG = clang++
CIRCT_TOOLS = /nix/store/9p497d8hxgpvyr441sa5nd41j0lyl6y3-circt-1.140.0/bin

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

FIRMWARE_DIR = ../fpga/firmware

.PHONY: all clean triangle vkcube $(FIRMWARE_DIR)/%.bin

all: triangle vkcube

# Step 1: FIRRTL → HW MLIR
borg.mlir: ../out/hardware/borg/firrtl/tt_um_gonsolo_borg.fir
	$(FIRTOOL) $< --ir-hw > $@

# Step 2: HW MLIR → LLVM IR + state info
arc.ll state.json: borg.mlir
	$(ARCILATOR) $< --state-file state.json -o arc.ll

# Step 3: LLVM IR → object
arc.o: arc.ll
	$(LLC) -O3 -relocation-model=pic --filetype=obj $< -o $@

# Step 4: state.json → C++ header
arc.h: state.json
	python3 /home/gonsolo/src/circt/tools/arcilator/arcilator-header-cpp.py $< > $@

# Step 5: Build test harness
main.o: main.cpp arc.h arcilator-runtime.h
	$(CLANG) -O3 -c main.cpp -o $@

# Step 6: Link
arcilator_sim: main.o arc.o
	$(CLANG) -O3 main.o arc.o -o $@

# Dependency on firmware binaries
$(FIRMWARE_DIR)/%.bin:
	$(MAKE) -C $(FIRMWARE_DIR) $*

# Step 7: Run targets
triangle: arcilator_sim $(FIRMWARE_DIR)/triangle.bin
	./arcilator_sim $(FIRMWARE_DIR)/triangle.bin triangle

vkcube: arcilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./arcilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube

clean:
	rm -f arc.h arc.ll arc.o borg.mlir state.json main.o arcilator_sim *.ppm
