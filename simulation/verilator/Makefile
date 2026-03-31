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

# Software dependency (implicitly requires hardware mmio mappings)
$(FIRMWARE_DIR)/%.bin: $(HW_DIR)/borg/verilog/asic_files.txt
	$(MAKE) -C $(FIRMWARE_DIR) $*

# Simulation targets
triangle: verilator_sim $(FIRMWARE_DIR)/triangle.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/triangle.bin triangle

vkcube: verilator_sim $(FIRMWARE_DIR)/vkcube.bin
	./obj_dir/verilator_sim $(FIRMWARE_DIR)/vkcube.bin vkcube

clean:
	rm -rf obj_dir verilator_sim *.ppm
