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

export CLOCK_MHZ = 4

generate_verilog:
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) borg.runMain borg.Main
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) tinyqv.runMain tinyqv.Main

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
	$(MILL) borg.test

# Extract Verilog sources from info.yaml for linting
# We select lines after 'source_files:' but before 'pinout:'
YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/' | sed 's|^\.\./||')

lint: generate_verilog
	verilator --lint-only -Wall -Iout/tinyqv/verilog -Iout/borg/verilog --top-module tt_um_tt_tinyQV lint.vlt $(YAML_SOURCES)

test-chisel-core:
	$(MILL) tinyqv.test

test-all: lint
	$(MILL) borg.test tinyqv.test
	$(TEST_SOC) core
	$(TEST_SOC) borg

datasheet.pdf: generate_verilog
	$(TT_TOOL) --create-pdf
user_config: generate_verilog
	$(TT_TOOL) --create-user-config --ihp --no-docker
gds: user_config
	$(TT_TOOL) --harden --ihp --no-docker
print_stats:
	./tt/tt_tool.py --print-stats

.PHONY: all generate_verilog help print_stats gds user_config lint test-all \
	test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl \
	test-cocotb-soc-core-gl test-cocotb-soc-borg-gl test-chisel-borg test-chisel-core
