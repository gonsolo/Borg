TT_TOOL   := ./tt/tt_tool.py
TEST_SOC  := make -C test/soc -B
MILL_JOBS := $(if $(CI),1,4)
MILL_OPTS := $(if $(CI),--no-server,) -j $(MILL_JOBS)
MILL      := mill $(MILL_OPTS)

BOLD := \033[1m
RESET   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  gds:\t\t\t\tGenerate the GDS II file for Tinytapeout.$(RESET)"
	@echo -e "  generate_verilog:\t\tGenerate Verilog from Chisel source."
	@echo -e "  test-chisel-borg:\t\tRun Borg tests (Chisel)."
	@echo -e "  test-chisel-core:\t\tRun TinyQV tests (Chisel)."
	@echo -e "  test-cocotb-soc-core-rtl:\tRun CPU core tests (cocotb)."
	@echo -e "  test-cocotb-soc-borg-rtl:\tRun Borg peripheral tests (cocotb)."
	@echo -e "  test-cocotb-soc-core-gl:\tRun Gate-Level core simulations (cocotb)."
	@echo -e "  test-cocotb-soc-borg-gl:\tRun Gate-Level borg simulations (cocotb)."
	@echo -e "  test-all:\t\t\tRun all tests (quiet summary with ✓/✗ per suite)."
	@echo -e "  datasheet.pdf:\t\tGenerate datasheet for Tinytapeout."
	@echo -e "  user_config:\t\t\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\t\tPrint statistics about tile usage."
	@echo -e "  book:\t\t\t\tBuild the documentation book."
	@echo -e "  clean:\t\t\tRemove all build artifacts."
	@echo -e "  rdl:\t\t\t\tValidate SystemRDL and generate Chisel register block."
	@echo -e "  clean-gh-runs:\t\tDelete all GitHub workflow runs except the last 8."

export CLOCK_MHZ = 4

generate_verilog: rdl
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) hardware.soc.runMain soc.Main
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) hardware.tinyqv.runMain tinyqv.Main
	CLOCK_MHZ=$(CLOCK_MHZ) $(MILL) fpga.tinyqv.runMain soc.FpgaMain
	# Must run after mill: reads asic_files.txt generated above.
	@python3 scripts/update_info_yaml.py

test-cocotb-soc-core-rtl: generate_verilog
	$(TEST_SOC) core

test-cocotb-soc-borg-rtl: generate_verilog
	$(TEST_SOC) borg

test-cocotb-soc-core-gl:
	$(TEST_SOC) core GATES=yes
	@ln -sf soc/results.xml test/results.xml

test-cocotb-soc-borg-gl:
	$(TEST_SOC) borg GATES=yes

test-chisel-borg:
	$(MILL) hardware.borg.test

lint: generate_verilog
	verilator --lint-only -Wall -Iout/hardware/tinyqv/verilog -Iout/hardware/borg/verilog --top-module tt_um_gonsolo_borg lint.vlt $$(cat out/hardware/borg/verilog/asic_files.txt | sed 's|^\.\./||') $$(for f in $$(cat out/hardware/tinyqv/verilog/asic_files.txt | xargs -I{} basename {}); do [ ! -f out/hardware/borg/verilog/$$f ] && echo out/hardware/tinyqv/verilog/$$f; done; true)

test-chisel-core:
	$(MILL) hardware.tinyqv.test

test-all:
	@MILL_JOBS=$(MILL_JOBS) python3 scripts/test_runner.py

datasheet.pdf: generate_verilog
	$(TT_TOOL) --create-pdf
user_config: generate_verilog
	$(TT_TOOL) --create-user-config --ihp --no-docker
gds: user_config
	$(TT_TOOL) --harden --ihp --no-docker
print_stats:
	./tt/tt_tool.py --print-stats
book:
	python3 docs/build_book.py

# --- SystemRDL → Chisel register generation ---
# systemrdl-compiler and peakrdl-cheader are provided by Nix (flake.nix).
# PeakRDL-chisel is a git submodule at repo root.
RDL_CHISEL   := $(CURDIR)/PeakRDL-chisel/src
RDL_DIR      := hardware/rdl
RDL_SRC      := $(wildcard $(RDL_DIR)/*.rdl)
RDL_SCALA_OUT:= hardware/borg/src/generated
RDL_C_OUT    := out/hardware/borg/rdl
RDL_PYTHON   := PYTHONPATH=$(RDL_CHISEL):$$PYTHONPATH python3

rdl: $(RDL_SRC)
	@echo "=== Validating SystemRDL ==="
	$(RDL_PYTHON) $(RDL_DIR)/validate_rdl.py
	@echo "=== Generating Chisel register blocks and C headers ==="
	mkdir -p $(RDL_C_OUT)
	$(RDL_PYTHON) $(RDL_DIR)/generate.py $(RDL_SCALA_OUT) $(RDL_C_OUT)
	@echo "Output: $(RDL_C_OUT)/ and $(RDL_SCALA_OUT)/"

clean:
	rm -f src/config_merged.json src/user_config.json
	rm -rf $(RDL_C_OUT)
	rm -rf $(RDL_SCALA_OUT)
	rm -rf out/
	$(MAKE) -C fpga clean
	$(MAKE) -C test/soc clean
	$(MAKE) -C software clean
	$(MAKE) -C simulation clean

clean-gh-runs:
	gh run list --limit 200 --json databaseId --jq '.[8:] | .[].databaseId' | xargs -I {} gh run delete {}

.PHONY: all generate_verilog help print_stats gds user_config lint test-all clean rdl \
	test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl \
	test-cocotb-soc-core-gl test-cocotb-soc-borg-gl test-chisel-borg test-chisel-core \
	book clean-gh-runs scripts/test_summary.sh

