SRC_FILES = $(shell cat ../../out/hardware/borg/verilog/asic_files.txt ../../out/hardware/tinyqv/verilog/asic_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' ')

all: triangle vkcube

verilator_sim: main.cpp $(SRC_FILES)
	verilator -cc --exe -O3 -x-assign fast -x-initial fast --noassert -Wall --build -j 0 \
	-I../../out/hardware/tinyqv/verilog -I../../out/hardware/borg/verilog \
	--top-module tt_um_gonsolo_borg \
	../../lint.vlt \
	$(SRC_FILES) main.cpp \
	-o verilator_sim

triangle: verilator_sim
	./obj_dir/verilator_sim ../../software/borg/triangle.bin triangle

vkcube: verilator_sim
	./obj_dir/verilator_sim ../../software/borg/vkcube.bin vkcube

clean:
	rm -rf obj_dir verilator_sim *.ppm
