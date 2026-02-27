NIX                   := nix develop --ignore-environment --command
TT_TOOL               := ./tt/tt_tool.py
MAKE_PERIPHERAL       := make -C peripheral
MAKE_TEST             := make -C test -B
NIX_PERIPHERAL_TEST   := $(MAKE_PERIPHERAL) nix_borg_test nix_tt_test
NIX_GENERATE_VERILOG  := $(MAKE_PERIPHERAL) nix_generate_verilog

BOLD := \033[1m
NC   := \033[0m


all: help
help:
	@echo "commands (nix): "
	@echo -e "$(BOLD)  nix_tt_gds: 		Generate the GDS II file that is used by Tinytapeout to tapeout the chip.$(NC)"
	@echo "  nix_peripheral_test: 	Run all tests in the peripheral submodule."
	@echo "  nix_generate_verilog:	Generate Verilog from Chisel source."
	@echo "  nix_tt_test: 		Run cocotb tests on the generated Verilog."
	@echo "  nix_tt_core_test: 	Run cocotb tests for the SOC."
	@echo "  nix_tt_docs: 		Generate docs for Tinytapeout."
	@echo "  nix_tt_user_config: 	Generate user config for tapeout."
	@echo "  nix_print_stats: 	Print statistics about tile usage."

src/peripherals.v: borg/src/Peripherals.scala
	mill borg.runMain borg.Main

src/project.v: borg/src/Project.scala
	mill borg.runMain borg.ProjectMain

nix_peripheral_test:
	$(NIX_PERIPHERAL_TEST)
nix_generate_verilog: src/peripherals.v src/project.v
	$(NIX_GENERATE_VERILOG)
nix_tt_test: nix_generate_verilog
	$(NIX) $(MAKE_TEST) borg.test
nix_tt_core_test: nix_generate_verilog
	$(NIX) $(MAKE_TEST) core
nix_tt_docs: nix_generate_verilog
	$(NIX) $(TT_TOOL) --create-pdf
nix_tt_user_config:
	$(NIX) $(TT_TOOL) --create-user-config --ihp --no-docker
nix_tt_gds: nix_tt_user_config
	$(NIX) $(TT_TOOL) --harden --ihp --no-docker
nix_print_stats:
	$(NIX) ./tt/tt_tool.py --print-stats

.PHONY: all \
	nix_generate_verilog nix_peripheral_test nix_print_stats nix_tt_core_test nix_tt_docs \
	nix_tt_gds nix_tt_test nix_tt_user_config \
