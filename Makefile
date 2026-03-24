TT_TOOL   := ./tt/tt_tool.py
TEST_SOC  := make -C test/soc -B
MILL_JOBS := $(if $(CI),1,4)
MILL_OPTS := $(if $(CI),--no-server,) -j $(MILL_JOBS)
MILL      := mill $(MILL_OPTS)

BOLD := \033[1m
RESET   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  gds:\t\t\t\tGenerate the GDS II file for Tinytapeout.$(RESET)"
	@echo -e "  generate_verilog:\t\tGenerate Verilog from Chisel source."
	@echo -e "  test-chisel-borg:\t\tRun Borg tests (Chisel)."
	@echo -e "  test-chisel-core:\t\tRun TinyQV tests (Chisel)."
	@echo -e "  test-cocotb-soc-core-rtl:\tRun CPU core tests (cocotb)."
	@echo -e "  test-cocotb-soc-borg-rtl:\tRun Borg peripheral tests (cocotb)."
	@echo -e "  test-cocotb-soc-core-gl:\tRun Gate-Level core simulations (cocotb)."
	@echo -e "  test-cocotb-soc-borg-gl:\tRun Gate-Level borg simulations (cocotb)."
	@echo -e "  test-all:\t\t\tRun all tests."
	@echo -e "  datasheet.pdf:\t\tGenerate datasheet for Tinytapeout."
	@echo -e "  user_config:\t\t\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\t\tPrint statistics about tile usage."
	@echo -e "  book:\t\t\t\tBuild the documentation book."
	@echo -e "  clean:\t\t\tRemove all build artifacts."

export CLOCK_MHZ = 4

generate_verilog:
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) hardware.borg.runMain borg.Main
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) hardware.tinyqv.runMain tinyqv.Main
	@python3 scripts/update_info_yaml.py
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) fpga.tinyqv.runMain borg.FpgaMain

test-cocotb-soc-core-rtl: generate_verilog
	$(TEST_SOC) core

test-cocotb-soc-borg-rtl: generate_verilog
	$(TEST_SOC) borg

test-cocotb-soc-core-gl:
	$(TEST_SOC) core GATES=yes
	@ln -sf soc/results.xml test/results.xml

test-cocotb-soc-borg-gl:
	$(TEST_SOC) borg GATES=yes

test-chisel-borg:
	$(MILL) hardware.borg.test

lint: generate_verilog
	verilator --lint-only -Wall -Iout/hardware/tinyqv/verilog -Iout/hardware/borg/verilog --top-module tt_um_gonsolo_borg lint.vlt $$(cat out/hardware/borg/verilog/asic_files.txt out/hardware/tinyqv/verilog/asic_files.txt | sed 's|^\.\./||' | tr '\n' ' ')

test-chisel-core:
	$(MILL) hardware.tinyqv.test

test-all: lint test-chisel-borg test-chisel-core test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl
	$(MAKE) -C fpga test-all

datasheet.pdf: generate_verilog
	$(TT_TOOL) --create-pdf
user_config: generate_verilog
	$(TT_TOOL) --create-user-config --ihp --no-docker
gds: user_config
	$(TT_TOOL) --harden --ihp --no-docker
print_stats:
	./tt/tt_tool.py --print-stats
book:
	python3 docs/build_book.py

clean:
	rm -f src/config_merged.json src/user_config.json
	rm -rf out/
	$(MAKE) -C fpga clean
	$(MAKE) -C test/soc clean

.PHONY: all generate_verilog help print_stats gds user_config lint test-all clean \
	test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl \
	test-cocotb-soc-core-gl test-cocotb-soc-borg-gl test-chisel-borg test-chisel-core \
	book

