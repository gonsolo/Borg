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

nix_borg_test:
	$(NIX) $(BORG_TEST)
nix_generate_verilog:
	$(NIX) $(BORG_RUN)
nix_tt_test_only:
	$(NIX) $(TEST)
nix_tt_test: nix_generate_verilog nix_tt_test_only
clean:
	git clean -dfx
.PHONY: all clean
	nix_borg_test nix_generate_verilog nix_tt_docs nix_tt_gds nix_tt_test nix_tt_test_only
