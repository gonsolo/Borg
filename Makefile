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
	@echo "commands: "
	@echo -e "$(BOLD)  tt_gds:\t\tGenerate the GDS II file that is used by Tinytapeout to tapeout the chip.$(NC)"
	@echo -e "  peripheral_test:\tRun all tests in the peripheral submodule."
	@echo -e "  generate_verilog:\tGenerate Verilog from Chisel source."
	@echo -e "  tt_test:\t\tRun cocotb tests on the generated Verilog."
	@echo -e "  tt_core_test:\t\tRun cocotb tests for the SOC."
	@echo -e "  tt_docs:\t\tGenerate docs for Tinytapeout."
	@echo -e "  tt_user_config:\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\tPrint statistics about tile usage."

src/peripherals.v: borg/src/Peripherals.scala
	mill borg.runMain borg.PeripheralsMain

src/project.v: borg/src/Project.scala
	mill borg.runMain borg.ProjectMain

peripheral_test:
	$(NIX_PERIPHERAL_TEST)
generate_verilog: src/peripherals.v src/project.v
	$(NIX_GENERATE_VERILOG)
tt_test: generate_verilog
	$(NIX) $(MAKE_TEST) borg.test
tt_core_test: generate_verilog
	$(NIX) $(MAKE_TEST) core
tt_docs: generate_verilog
	$(NIX) $(TT_TOOL) --create-pdf
tt_user_config:
	$(NIX) $(TT_TOOL) --create-user-config --ihp --no-docker
tt_gds: tt_user_config
	$(NIX) $(TT_TOOL) --harden --ihp --no-docker
print_stats:
	$(NIX) ./tt/tt_tool.py --print-stats

.PHONY: all \
	generate_verilog peripheral_test print_stats tt_core_test tt_docs \
	tt_gds tt_test tt_user_config \
