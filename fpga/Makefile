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
	@echo -e "$(BOLD)  triangle_ppm:\tRender a single triangle frame.$(RESET)"
	@echo -e "    triangle_ppms:\tRender all 10 PPM frames."
	@echo -e "    animation.webp:\tEncode PPMs to animated WebP."
	@echo -e "  burn:\t\t\tGenerate bitstream and flash to FPGA."
	@echo -e "  tio:\t\t\tConnect to the serial console."
	@echo -e "  reset:\t\tReset the CPU."
	@echo -e "  borg.json:\t\tRun synthesis."
	@echo -e "  borg.asc:\t\tRun Place & Route (pnr)."
	@echo -e "  borg.bin:\t\tGenerate the FPGA bitstream."
	@echo -e "  resource_check:\tCheck LUT and DFF usage coefficients."
	@echo -e "  clean:\t\tRemove build artifacts."

firmware/borg_psram.bin:
	$(MAKE) -C firmware psram

triangle_ppm: firmware/borg_psram.bin
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_shader_test; run_shader_test.run_single_frame(0)" | tee /tmp/mpremote_output.txt

triangle_ppms: firmware/borg_psram.bin
	mpremote mount . + exec "import sys; sys.path.append('host'); import run_shader_test; run_shader_test.render_frames()" | tee /tmp/mpremote_output.txt

animation.webp: triangle_ppms
	magick triangle_*.ppm -delay 10 -loop 0 animation.webp

watch_animation:
	loupe animation.webp
watch_ppm:
	loupe triangle_0*.ppm

tio:
	tio /dev/ttyACM1 | tee /tmp/tio_output.txt

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
	@grep "SB_LUT4" yosys.log | tail -n1 | awk -v max=$(MAX_LUTS) '{ \
	        percent=($$1/max)*100; \
	        printf "SB_LUT4 (Logic): \t%d \t%.0f%%\n", $$1, percent \
	}'
	@grep "SB_DFF" yosys.log | awk -v max=$(MAX_DFFS) '{sum+=$$$$2;} END { \
	        percent=(sum/max)*100; \
	        printf "SB_DFF* (Registers): \t%d \t%.0f%%\n", sum, percent \
	}'

clean:
	rm -f borg.bin borg.json borg.asc yosys.log nextpnr.log triangle*.ppm animation.webp
	$(MAKE) -C firmware clean
	$(MAKE) -C sdk clean

.PHONY: all help burn clean reset resource_check firmware/borg_psram.bin triangle_ppms triangle_ppm watch_animation watch_ppm tio
