ROOT      := $(shell git rev-parse --show-toplevel)
RDL_C_OUT ?= $(ROOT)/out/hardware/borg/rdl
HW_DIR    ?= $(ROOT)/out/hardware
FIRMWARE_DIR ?= $(ROOT)/software/borg

# Lazily evaluate SRC_FILES, avoiding failures if asic_files.txt doesn't exist yet
# Use `wildcard` to trigger rebuilds if ANY verilog file changes in the output dir
V_SRCS = $(wildcard $(HW_DIR)/borg/verilog/*.v)

all: triangle vkcube

$(HW_DIR)/borg/verilog/asic_files.txt:
	$(MAKE) -C $(ROOT) generate_verilog

# Verilator simulator depends on hardware generation and the actual verilog files
obj_dir/verilator_sim: main.cpp $(HW_DIR)/borg/verilog/asic_files.txt $(V_SRCS)
	@rm -rf obj_dir
	@SRC_FILES=$$(cat $(HW_DIR)/borg/verilog/asic_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator models..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall --build -j 0 \
	-MAKEFLAGS "-s" \
	-I$(HW_DIR)/borg/verilog \
	-I$(HW_DIR)/borg/verilog/verification/assert -I$(HW_DIR)/borg/verilog/verification \
	--top-module tt_um_gonsolo_borg \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	$(wildcard $(HW_DIR)/borg/verilog/verification/layers-*.sv) \
	main.cpp \
	../common/BorgSimulatorBase.cpp \
	../common/texture_loader.cpp \
	../common/uart_decoder.cpp \
	-o verilator_sim

# Software dependency (implicitly requires hardware mmio mappings)
$(FIRMWARE_DIR)/%.bin: $(HW_DIR)/borg/verilog/asic_files.txt
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

.PHONY: all triangle vkcube vkcube_gui clean

clean:
	rm -rf obj_dir build verilator_sim *.ppm *.so

