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
# We use the verilog_sim directory because it includes the memSim RAM array logic!
verilator_sim: main.cpp $(HW_DIR)/borg/verilog_sim/asic_files.txt $(V_SRCS)
	@rm -rf obj_dir
	@SRC_FILES=$$(cat $(HW_DIR)/borg/verilog_sim/asic_files.txt $(HW_DIR)/tinyqv/verilog/asic_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator models..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall --build -j 0 \
	-I$(HW_DIR)/borg/verilog_sim -I$(HW_DIR)/tinyqv/verilog \
	-I$(HW_DIR)/borg/verilog_sim/verification/assert -I$(HW_DIR)/borg/verilog_sim/verification \
	-I$(HW_DIR)/tinyqv/verilog/verification/assert -I$(HW_DIR)/tinyqv/verilog/verification \
	--top-module tt_um_gonsolo_borg \
	-CFLAGS "-fPIC" \
	-DENABLE_INITIAL_MEM_ \
	../../lint.vlt \
	$$SRC_FILES \
	$(HW_DIR)/borg/verilog_sim/verification/layers-*.sv \
	main.cpp \
	-o verilator_sim

# Software dependency (implicitly requires hardware mmio mappings)
$(FIRMWARE_DIR)/%.bin: $(HW_DIR)/borg/verilog/asic_files.txt
	$(MAKE) -C $(FIRMWARE_DIR) $*

# Simulation targets
triangle: verilator_sim python_extension $(FIRMWARE_DIR)/triangle.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/triangle.bin triangle 32 32 32

vkcube: verilator_sim python_extension $(FIRMWARE_DIR)/vkcube.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube 32 32 256

vkcube_gui: python_extension $(FIRMWARE_DIR)/vkcube.bin
	python3 viewer.py

vkcube_gui_fast: python_extension $(FIRMWARE_DIR)/vkcube.bin
	python3 viewer.py --fast

python_extension: verilator_sim
	mkdir -p build && cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$$(which python3) -DVERILATOR_ROOT=$$(dirname $$(dirname $$(which verilator)))/share/verilator -DCMAKE_EXPORT_COMPILE_COMMANDS=ON && make
	@ln -sf build/compile_commands.json compile_commands.json

.PHONY: all triangle vkcube vkcube_gui clean

clean:
	rm -rf obj_dir build build_fast verilator_sim verilator_sim_fast *.ppm *.so

