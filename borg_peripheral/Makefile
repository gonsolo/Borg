MILL               := mill --no-server
NIX                := nix develop --ignore-environment --command
TT_TOOL            := python tt/tt_tool.py
BORG_TEST          := $(MILL) borg.test
BORG_RUN           := $(MILL) borg.run
TEST               := make -C test
DOCS               := $(TT_TOOL) --create-pdf
CREATE_USER_CONFIG := $(TT_TOOL) --create-user-config --ihp --no-docker
HARDEN             := $(TT_TOOL) --harden --ihp --no-docker

all: nix_borg_test nix_tt_test nix_tt_docs nix_tt_gds

arch_borg_test:
	$(BORG_TEST)
nix_borg_test:
	$(NIX) $(BORG_TEST)
arch_generate_verilog:
	$(BORG_RUN)
nix_generate_verilog:
	$(NIX) $(BORG_RUN)
arch_tt_test_only:
	$(TEST)
nix_tt_test_only:
	$(NIX) $(TEST)
arch_tt_test: arch_generate_verilog arch_tt_test_only
nix_tt_test: nix_generate_verilog nix_tt_test_only
arch_tt_docs: arch_generate_verilog
	$(DOCS)
nix_tt_docs: arch_generate_verilog
	$(NIX) $(DOCS)
arch_tt_gds:
	$(CREATE_USER_CONFIG)
	$(HARDEN)
nix_tt_gds:
	$(NIX) $(CREATE_USER_CONFIG)
	$(NIX) $(HARDEN)
arch_print_stats:
	./tt/tt_tool.py --print-stats
clean:
	git clean -dfx
.PHONY: all clean
	arch_borg_test arch_generate_verilog arch_print_stats arch_tt_docs arch_tt_gds arch_tt_test arch_tt_test_only \
	nix_borg_test nix_generate_verilog nix_tt_docs nix_tt_gds nix_tt_test nix_tt_test_only
