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
	@echo -e "$(BOLD)  gds:\t\t\tGenerate the GDS II file for Tinytapeout.$(NC)"
	@echo -e "  generate_verilog:\tGenerate Verilog from Chisel source."
	@echo -e "  test-chisel:\t\tRun Chisel hardware tests."
	@echo -e "  test-tinyqv:\t\tRun TinyQV Chisel tests."
	@echo -e "  test-borg:\t\tRun peripheral tests (cocotb)."
	@echo -e "  test-cpu:\t\tRun CPU core tests (cocotb)."
	@echo -e "  test-system:\t\tRun SoC integration tests (cocotb)."
	@echo -e "  test-all:\t\tRun all tests."
	@echo -e "  datasheet.pdf:\tGenerate datasheet for Tinytapeout."
	@echo -e "  user_config:\t\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\tPrint statistics about tile usage."

CLOCK_MHZ ?= 4

generate_verilog:
	$(RUN) "export CLOCK_MHZ=$(CLOCK_MHZ); $(MILL) borg.runMain borg.Main"
	$(RUN) "$(MILL) tinyqv.runMain tinyqv.Main"

test-borg:
	CLOCK_MHZ=64 $(MAKE) generate_verilog
	$(RUN) "$(MILL) harness.runMain harness.Main"
	$(RUN) "$(TEST_PERIPHERAL)"

test-system:
	CLOCK_MHZ=64 $(MAKE) generate_verilog
	$(RUN) "$(TEST_SOC) borg.test"

test-cpu:
	CLOCK_MHZ=64 $(MAKE) generate_verilog
	$(RUN) "$(TEST_SOC) core"

test-chisel:
	$(RUN) "$(MILL) borg.test"

test-tinyqv:
	$(RUN) "$(MILL) tinyqv.test"

test-all: test-chisel test-tinyqv test-cpu test-borg test-system

datasheet.pdf: generate_verilog
	$(RUN) "$(TT_TOOL) --create-pdf"
user_config: generate_verilog
	$(RUN) "$(TT_TOOL) --create-user-config --ihp --no-docker"
gds: user_config
	$(RUN) "$(TT_TOOL) --harden --ihp --no-docker"
print_stats:
	$(RUN) "./tt/tt_tool.py --print-stats"

.PHONY: all generate_verilog print_stats gds user_config \
	test-all test-borg test-system test-cpu test-chisel test-tinyqv
