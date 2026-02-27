NIX                   	:= nix develop --ignore-environment --command
TT_TOOL               	:= ./tt/tt_tool.py
MAKE_TEST             	:= make -C test -B
MILL               	:= mill --no-server

BOLD := \033[1m
NC   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  tt_gds:\t\tGenerate the GDS II file that is used by Tinytapeout to tapeout the chip.$(NC)"
	@echo -e "  generate_verilog:\tGenerate Verilog from Chisel source."
	@echo -e "  borg_test:\t\tRun Chisel tests."
	@echo -e "  peripheral_test:\tRun peripheral cocotb tests."
	@echo -e "  tt_test:\t\tRun SOC peripheral cocotb tests."
	@echo -e "  tt_core_test:\t\tRun SOC cocotb tests."
	@echo -e "  tt_docs:\t\tGenerate docs for Tinytapeout."
	@echo -e "  tt_user_config:\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\tPrint statistics about tile usage."

generate_verilog:
	$(NIX) $(MILL) borg.runMain borg.Main

borg_test:
	$(NIX) $(MILL) borg.test
peripheral_test:
	make -C peripheral nix_tt_test

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
