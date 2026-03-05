NIX                     := nix develop --ignore-environment --command bash -c
ifeq ($(IN_NIX_SHELL),)
    RUN = $(NIX)
else
    RUN = bash -c
endif

TT_TOOL               	:= ./tt/tt_tool.py
TEST_SOC             	:= make -C test/soc -B
TEST_PERIPHERAL        	:= make -C test/peripheral -B
MILL               	:= mill --no-server

BOLD := \033[1m
NC   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  gds:\t\t\t\tGenerate the GDS II file for Tinytapeout.$(NC)"
	@echo -e "  generate_verilog:\t\tGenerate Verilog from Chisel source."
	@echo -e "  test-chisel-borg:\t\tRun Borg tests (Chisel)."
	@echo -e "  test-chisel-tinyqv:\t\tRun TinyQV tests (Chisel)."
	@echo -e "  test-cocotb-peripheral-rtl:\tRun peripheral tests (cocotb)."
	@echo -e "  test-cocotb-soc-core-rtl:\tRun CPU core tests (cocotb)."
	@echo -e "  test-cocotb-soc-borg-rtl:\tRun Borg peripheral tests (cocotb)."
	@echo -e "  test-cocotb-soc-core-gl:\t\tRun Gate-Level simulations (cocotb)."
	@echo -e "  test-all:\t\t\tRun all tests."
	@echo -e "  datasheet.pdf:\t\tGenerate datasheet for Tinytapeout."
	@echo -e "  user_config:\t\t\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\t\tPrint statistics about tile usage."

export CLOCK_MHZ = 12

generate_verilog:
	$(RUN) "CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) borg.runMain borg.Main"
	$(RUN) "CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) tinyqv.runMain tinyqv.Main"

test-cocotb-peripheral-rtl: generate_verilog
	$(RUN) "$(MILL) harness.runMain harness.Main"
	$(RUN) "$(TEST_PERIPHERAL)"

test-cocotb-soc-core-rtl: generate_verilog
	$(RUN) "$(TEST_SOC) core"

test-cocotb-soc-borg-rtl: generate_verilog
	$(RUN) "$(TEST_SOC) borg"

test-cocotb-soc-core-gl:
	$(RUN) "$(TEST_SOC) core GATES=yes"
	@ln -sf soc/results.xml test/results.xml



test-chisel-borg:
	$(RUN) "$(MILL) borg.test"

# Extract Verilog sources from info.yaml for linting
# We select lines after 'source_files:' but before 'pinout:'
YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/' | sed 's|^\.\./||')

lint: generate_verilog
	$(RUN) "verilator --lint-only -Wall -Iout/tinyqv/verilog -Iout/borg/verilog --top-module tt_um_tt_tinyQV lint.vlt $(YAML_SOURCES)"

test-chisel-tinyqv:
	$(RUN) "$(MILL) tinyqv.test"

test-all: lint test-chisel-borg test-chisel-tinyqv test-cocotb-peripheral-rtl test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl

datasheet.pdf: generate_verilog
	$(RUN) "$(TT_TOOL) --create-pdf"
user_config: generate_verilog
	$(RUN) "$(TT_TOOL) --create-user-config --ihp --no-docker"
gds: user_config
	$(RUN) "$(TT_TOOL) --harden --ihp --no-docker"
print_stats:
	$(RUN) "./tt/tt_tool.py --print-stats"

.PHONY: all generate_verilog help print_stats gds user_config lint \
	test-all test-cocotb-peripheral-rtl test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl test-cocotb-soc-core-gl test-chisel-borg test-chisel-tinyqv
