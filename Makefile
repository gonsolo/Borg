TT_TOOL := ./tt/tt_tool.py

all: peripheral_test tt_test tt_docs
peripheral_test:
	make -C borg_peripheral borg_test tt_test
generate_verilog:
	make -C borg_peripheral generate_verilog
tt_test: generate_verilog
	make -C test -B borg.test
tt_docs:
	$(TT_TOOL) --create-pdf
tt_gds:
	$(TT_TOOL) --create-user-config --ihp
	$(TT_TOOL) --harden --ihp
nix:
#	nix develop --ignore-environment --command make peripheral_test
#	nix develop --ignore-environment --command make tt_test
#	nix develop --ignore-environment --command make tt_docs
	nix develop --ignore-environment --command make tt_gds

print_stats:
	./tt/tt_tool.py --print-stats

core: generate_verilog
	make -C test -B core PROG=hello

.PHONY: all peripheral_test generate_verilog tt_test tt_docs tt_gds nix print_stats core

