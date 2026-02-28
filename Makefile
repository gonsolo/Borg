NIX                   	:= nix develop --ignore-environment --command
TT_TOOL               	:= ./tt/tt_tool.py
MAKE_TEST             	:= make -C test/soc -B
MILL               	:= mill --no-server

BOLD := \033[1m
NC   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  gds:\t\t\tGenerate the GDS II file for Tinytapeout.$(NC)"
	@echo -e "  generate_verilog:\tGenerate Verilog from Chisel source."
	@echo -e "  test-chisel:\t\tRun Chisel hardware tests."
	@echo -e "  test-fpu:\t\tRun FPU peripheral tests (cocotb)."
	@echo -e "  test-cpu:\t\tRun CPU core tests (cocotb)."
	@echo -e "  test-system:\t\tRun SoC integration tests (cocotb)."
	@echo -e "  datasheet.pdf:\tGenerate datasheet for Tinytapeout."
	@echo -e "  user_config:\t\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\tPrint statistics about tile usage."

# Generate Verilog Artifacts
generate_verilog:
	$(NIX) $(MILL) borg.runMain borg.Main
	$(NIX) $(MILL) tinyqv.runMain tinyqv.Main

# New Test Targets
test-fpu: generate_verilog
	$(NIX) $(MILL) harness.runMain harness.Main
	$(NIX) make -C test/peripheral

test-system: generate_verilog
	$(NIX) $(MAKE_TEST) borg.test

test-cpu: generate_verilog
	$(NIX) $(MAKE_TEST) core

test-chisel:
	$(NIX) $(MILL) borg.test

datasheet.pdf: generate_verilog
	$(NIX) $(TT_TOOL) --create-pdf
user_config: generate_verilog
	$(NIX) $(TT_TOOL) --create-user-config --ihp --no-docker
gds: user_config
	$(NIX) $(TT_TOOL) --harden --ihp --no-docker
print_stats:
	$(NIX) ./tt/tt_tool.py --print-stats

.PHONY: all generate_verilog print_stats \
	gds user_config \
	test-fpu test-system test-cpu test-chisel
