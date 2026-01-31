TT_TOOL := ./tt/tt_tool.py

all: peripheral_test tt_test tt_docs tt_gds
peripheral_test:
	make -C borg_peripheral borg_test tt_test
generate_verilog:
	make -C borg_peripheral generate_verilog
tt_test: generate_verilog tt_core_test
	make -C test -B borg.test
tt_core_test: generate_verilog
	make -C test -B core # PROG=hello
tt_docs:
	$(TT_TOOL) --create-pdf
tt_user_config:
	$(TT_TOOL) --create-user-config --ihp --no-docker
tt_gds: tt_user_config
	$(TT_TOOL) --harden --ihp --no-docker
nix:
#	nix develop --ignore-environment --command make all
	nix develop --ignore-environment --command make tt_gds

print_stats:
	./tt/tt_tool.py --print-stats

.PHONY: all peripheral_test generate_verilog tt_test tt_docs tt_gds nix print_stats core

