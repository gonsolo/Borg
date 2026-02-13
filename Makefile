NIX                   := nix develop --ignore-environment --command
TT_TOOL               := ./tt/tt_tool.py
MAKE_PERIPHERAL       := make -C borg_peripheral
MAKE_TEST             := make -C test -B
ARCH_PERIPHERAL_TEST  := $(MAKE_PERIPHERAL) arch_borg_test arch_tt_test
NIX_PERIPHERAL_TEST   := $(MAKE_PERIPHERAL) nix_borg_test nix_tt_test
ARCH_GENERATE_VERILOG := $(MAKE_PERIPHERAL) arch_generate_verilog
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
	@echo "commands (arch): The same without nix"

arch_peripheral_test:
	$(ARCH_PERIPHERAL_TEST)
nix_peripheral_test:
	$(NIX_PERIPHERAL_TEST)
arch_generate_verilog:
	$(ARCH_GENERATE_VERILOG)
nix_generate_verilog:
	$(NIX_GENERATE_VERILOG)
arch_tt_test: arch_generate_verilog
	$(MAKE_TEST) borg.test
nix_tt_test: nix_generate_verilog
	$(NIX) $(MAKE_TEST) borg.test
arch_tt_core_test: arch_generate_verilog
	$(MAKE_TEST) core
nix_tt_core_test: nix_generate_verilog
	$(NIX) $(MAKE_TEST) core
arch_tt_docs:
	$(TT_TOOL) --create-pdf
nix_tt_docs:
	$(NIX) $(TT_TOOL) --create-pdf
arch_tt_user_config:
	$(TT_TOOL) --create-user-config --ihp --no-docker
nix_tt_user_config:
	$(NIX) $(TT_TOOL) --create-user-config --ihp --no-docker
arch_tt_gds: arch_tt_user_config
	$(TT_TOOL) --harden --ihp --no-docker
nix_tt_gds: nix_tt_user_config
	$(NIX) $(TT_TOOL) --harden --ihp --no-docker
arch_print_stats:
	./tt/tt_tool.py --print-stats
nix_print_stats:
	$(NIX) ./tt/tt_tool.py --print-stats

.PHONY: all \
	arch_generate_verilog arch_peripheral_test arch_print_stats arch_tt_core_test arch_tt_docs \
	arch_tt_gds arch_tt_test arch_tt_user_config \
	nix_generate_verilog nix_peripheral_test nix_print_stats nix_tt_core_test nix_tt_docs \
	nix_tt_gds nix_tt_test nix_tt_user_config \
