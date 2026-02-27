NIX                := nix develop --ignore-environment --command
TEST               := make -C test

all: nix_borg_test nix_tt_test

nix_tt_test_only:
	$(NIX) $(TEST)
nix_tt_test: nix_tt_test_only
