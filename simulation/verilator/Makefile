HW_DIR = ../../out/hardware
FIRMWARE_DIR = ../../software/borg

# Lazily evaluate SRC_FILES, avoiding failures if asic_files.txt doesn't exist yet
# Use `wildcard` to trigger rebuilds if ANY verilog file changes in the output dir
V_SRCS = $(wildcard $(HW_DIR)/borg/verilog/*.v $(HW_DIR)/tinyqv/verilog/*.v)

all: triangle vkcube

# Hardware dependency
$(HW_DIR)/borg/verilog/asic_files.txt:
	$(MAKE) -C ../../ generate_verilog

# Verilator simulator depends on hardware generation and the actual verilog files
verilator_sim: main.cpp $(HW_DIR)/borg/verilog/asic_files.txt $(V_SRCS)
	@SRC_FILES=$$(cat $(HW_DIR)/borg/verilog/asic_files.txt $(HW_DIR)/tinyqv/verilog/asic_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator models..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast --noassert -Wall --build -j 0 \
	-I$(HW_DIR)/tinyqv/verilog -I$(HW_DIR)/borg/verilog \
	--top-module tt_um_gonsolo_borg \
	../../lint.vlt \
	$$SRC_FILES main.cpp \
	-o verilator_sim

# Fast Verilator simulator (bypasses QSPI)
verilator_sim_fast: main.cpp $(HW_DIR)/borg/verilog_sim/asic_files.txt $(V_SRCS)
	@SRC_FILES=$$(cat $(HW_DIR)/borg/verilog_sim/asic_files.txt $(HW_DIR)/tinyqv/verilog/asic_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Fast Verilator models..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast --noassert -Wall --build -j 0 \
	-I$(HW_DIR)/tinyqv/verilog -I$(HW_DIR)/borg/verilog_sim \
	--top-module tt_um_gonsolo_borg_sim \
	-CFLAGS "-DFAST_MEM=1" \
	../../lint.vlt \
	$$SRC_FILES main.cpp \
	-o verilator_sim_fast

# Software dependency (implicitly requires hardware mmio mappings)
$(FIRMWARE_DIR)/%.bin: $(HW_DIR)/borg/verilog/asic_files.txt
	$(MAKE) -C $(FIRMWARE_DIR) $*

# Simulation targets
triangle: verilator_sim python_extension $(FIRMWARE_DIR)/triangle.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/triangle.bin triangle

vkcube: verilator_sim python_extension $(FIRMWARE_DIR)/vkcube.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube

vkcube_gui: python_extension $(FIRMWARE_DIR)/vkcube.bin
	python3 viewer.py

vkcube_gui_fast: python_extension_fast $(FIRMWARE_DIR)/vkcube.bin
	python3 viewer.py --fast

python_extension: verilator_sim
	@rm -rf build && mkdir -p build && cd build && cmake .. \
		-DCMAKE_CXX_COMPILER=g++ \
		-DPython_EXECUTABLE=$$(which python3) \
		-DVERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator \
		&& make

python_extension_fast: verilator_sim_fast
	@rm -rf build_fast && mkdir -p build_fast && cd build_fast && cmake .. -DFAST_MEM=1 \
		-DCMAKE_CXX_COMPILER=g++ \
		-DPython_EXECUTABLE=$$(which python3) \
		-DVERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator \
		&& make

.PHONY: all triangle vkcube vkcube_gui clean

clean:
	rm -rf obj_dir build build_fast verilator_sim verilator_sim_fast *.ppm *.so

