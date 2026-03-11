# Environment setup (handled by direnv)
BORG_DIR 	:= ../src/user_peripherals/borg
TINYQV_DIR 	:= ../out/tinyqv/verilog

SRC_FILES = pico_ice.v \
	    $(wildcard ../out/borg/verilog/*.sv) \
	    $(wildcard ../out/tinyqv/verilog/*.sv) \

# Hardware limits
MAX_LUTS = 5280
MAX_DFFS = 5280

BOLD := \033[1m
RESET   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  run_shader_test:\tPSRAM round-trip rotation test on FPGA.$(RESET)"
	@echo -e "    triangle_ppms:\tGenerate all 10 PPM frames."
	@echo -e "    triangle_ppm:\tGenerate a single PPM frame (FRAME=N)."
	@echo -e "    triangle_animation.mp4:\tEncode PPMs to video."
	@echo -e "  run_borg_addition:\tCompile and run borg_addition on FPGA."
	@echo -e "  run_borg_rotation:\tCompile and run borg_rotate on FPGA."
	@echo -e "  burn:\t\t\tGenerate bitstream and flash to FPGA."
	@echo -e "  tio:\t\t\tConnect to the serial console."
	@echo -e "  reset:\t\tReset the CPU."
	@echo -e "  borg.json:\t\tRun synthesis."
	@echo -e "  borg.asc:\t\tRun Place & Route (pnr)."
	@echo -e "  borg.bin:\t\tGenerate the FPGA bitstream."
	@echo -e "  resource_check:\tCheck LUT and DFF usage coefficients."
	@echo -e "  clean:\t\tRemove build artifacts."

# And you have to burn first, of course
run_borg_addition:
	make -C firmware
	cd firmware && python convert_test_data.py --reference
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_tinyqv; run_tinyqv.execute('firmware/borg_test.bin')"

run_borg_rotation:
	make -C firmware rotate
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_tinyqv; run_tinyqv.execute('firmware/borg_rotate.bin')"

FRAME ?= 0

run_shader_test: triangle_animation.mp4

firmware/borg_psram.bin:
	$(MAKE) -C firmware psram

triangle_ppms: firmware/borg_psram.bin
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_shader_test"

triangle_ppm: firmware/borg_psram.bin
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_shader_test; run_shader_test.run_single_frame($(FRAME))"

triangle_animation.mp4: triangle_ppms
	ffmpeg -y -framerate 2 -i triangle_%02d.ppm -vf "scale=256:256:flags=neighbor" -c:v libx264 -pix_fmt yuv420p triangle_animation.mp4

watch:
	vlc --loop triangle_animation.mp4

# To see something when running you have to connect to the debug probe in second terminal
tio:
	tio /dev/ttyACM1

reset:
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_tinyqv; run_tinyqv.run(query=False, stop=False)"

burn: borg.bin
	mpremote mount . + run host/program_bitstream.py

borg.bin: borg.asc
	icepack $< $@

borg.asc: borg.json
	nextpnr-ice40 --up5k --package sg48 --json borg.json --pcf pico_ice.pcf --asc borg.asc --freq 4

borg.json: $(SRC_FILES)
	yosys -p "read -sv $(SRC_FILES) ; synth_ice40 -dsp -abc9 -device u -top tinyQV_top -json borg.json" \
	-DICE40 -DPURE_RTL -DSYNTH_FPGA > yosys.log

resource_check: yosys.log
	@# Process LUTs: $1 is the count in the yosys log
	@grep "SB_LUT4" yosys.log | tail -n1 | awk -v max=$(MAX_LUTS) '{ \
	        percent=($$1/max)*100; \
	        printf "SB_LUT4 (Logic): \t%d \t%.0f%%\n", $$1, percent \
	}'
	@# Process DFFs: $$$$2 is needed to pass literal $2 to awk for the count column
	@grep "SB_DFF" yosys.log | awk -v max=$(MAX_DFFS) '{sum+=$$$$2;} END { \
	        percent=(sum/max)*100; \
	        printf "SB_DFF* (Registers): \t%d \t%.0f%%\n", sum, percent \
	}'

clean:
	rm -f borg.bin borg.json borg.asc yosys.log nextpnr.log triangle*.ppm triangle_animation.mp4
	$(MAKE) -C firmware clean
	$(MAKE) -C sdk clean

.PHONY: all help burn clean reset resource_check run_borg_addition run_borg_rotation run_shader_test firmware/borg_psram.bin triangle_ppms triangle_ppm watch tio

