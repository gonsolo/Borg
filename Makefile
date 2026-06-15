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
	@echo -e "$(BOLD)  gds-sky130:\t\t\tGenerate Sky130 GDS II file for Tinytapeout.$(RESET)"
	@echo -e "$(BOLD)  gds-ihp:\t\t\tGenerate IHP SG13G2 GDS II file for Tinytapeout.$(RESET)"
	@echo -e "  ------------------------------------------------------------------------------"
	@echo -e "  rdl:\t\t\t\tValidate SystemRDL."
	@echo -e "  generate_verilog:\t\tGenerate Verilog from Chisel source."
	@echo -e "  test-chisel-borg:\t\tRun Borg tests (Chisel)."
	@echo -e "  test-chisel-core:\t\tRun Hutt CPU tests (Chisel)."
	@echo -e "  test-cocotb-soc-core-rtl:\tRun CPU core tests (cocotb)."
	@echo -e "  test-cocotb-soc-borg-rtl:\tRun Borg peripheral tests (cocotb)."
	@echo -e "  test-cocotb-soc-core-gl:\tRun Gate-Level core simulations (cocotb)."
	@echo -e "  test-cocotb-soc-borg-gl:\tRun Gate-Level borg simulations (cocotb)."
	@echo -e "  test-all:\t\t\tRun all tests."
	@echo -e "  datasheet.pdf:\t\tGenerate datasheet for Tinytapeout."
	@echo -e "  user_config-*:\t\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\t\tPrint statistics about tile usage."
	@echo -e "  book:\t\t\t\tBuild the documentation book."
	@echo -e "  clean:\t\t\tRemove all build artifacts."
	@echo -e "  clean-gh-runs:\t\tClean up GitHub workflow runs."

# Clock frequencies: each target's Scala Main has its own default.
# TT ASIC = 4 MHz, ULX3S = 25 MHz (SoC) / 125 MHz (HDMI).
# Override via env var if needed: CLOCK_MHZ=50 make generate_verilog

HAND_CHISEL = $(shell find hardware/borg/src hardware/soc/src hardware/hutt/src hardware/memory/src \
                        fpga/ulx3s/soc/src asic/tt/src \
                        -name '*.scala' -not -path '*/generated/*' 2>/dev/null)

# Stamp target: only re-runs Mill when Scala or RDL sources actually change.
# | rdl is an order-only dep so rdl always runs first (it is fast and idempotent)
# but a re-run of rdl alone does not invalidate the stamp.
.verilog_stamp: $(HAND_CHISEL) $(RDL_SRC) | rdl
	CLOCK_MHZ=4 $(MILL) asic.tt.runMain asic.tt.TTMain
	@python3 scripts/init_bram_zero.py out/hardware/borg/verilog
	@# firtool appends a tab+source-location to "// synthesis translate_on" lines.
	@# yowasp-yosys (used by tt_tool.py for port-read) does not recognise the
	@# augmented form and fails to exit translate-off mode, causing a parse error
	@# on the very next token.  Strip the trailing annotation here so the files
	@# are compatible with both yowasp-yosys and native Yosys.
	@sed -i 's|// synthesis translate_on\t.*|// synthesis translate_on|g' out/hardware/borg/verilog/*.sv
	@touch $@

.PHONY: info.yaml
info.yaml: .verilog_stamp
	@python3 scripts/update_info_yaml.py

# Convenience alias: ensures rdl and the verilog stamp are up to date.
# Still declared phony so `make generate_verilog` always checks deps explicitly.
generate_verilog: .verilog_stamp info.yaml

# Verilator simulation Verilog — flat MemBackendIO top (no QSPI), into
# out/hardware/borg/verilog_sim/.  Used by simulation/verilator.
.verilog_sim_stamp: $(HAND_CHISEL) $(RDL_SRC) | rdl
	CLOCK_MHZ=4 $(MILL) asic.tt.runMain asic.tt.BorgSimMain
	@touch $@

generate_verilog_sim: .verilog_sim_stamp

# ULX3S (ECP5-85K) Verilog emission stub — no synthesis flow yet (Step 27).
# ULX3S SoC clock (MHz). Single source of truth is ULX3S_MHZ in
# fpga/ulx3s/Makefile, which passes it here as ULX3S_CLOCK_MHZ.
ULX3S_CLOCK_MHZ ?= 25
generate_verilog_ulx3s: rdl
	CLOCK_MHZ=$(ULX3S_CLOCK_MHZ) $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SMain

# Minimal ULX3S Verilog — Hutt + UART only, no Borg.  Fast-iteration target.
generate_verilog_ulx3s_minimal:
	CLOCK_MHZ=25 $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SMinimalMain

# HDMI Test Pattern emission
generate_hdmi_test: rdl
	TARGET_DIR=out/ulx3s/hdmi_test $(MILL) fpga.ulx3s.soc.runMain soc.HdmiTestMain

# HDMI SDRAM Test emission
generate_hdmi_sdram_test: rdl
	TARGET_DIR=out/ulx3s/hdmi_sdram_test $(MILL) fpga.ulx3s.soc.runMain soc.HdmiSdramTestMain

# CPU SDRAM HDMI Test emission
generate_cpu_sdram_hdmi_test: rdl
	TARGET_DIR=out/ulx3s/cpu_sdram_hdmi_test $(MILL) fpga.ulx3s.soc.runMain soc.CpuSdramHdmiTestMain

# Minimal CPU+SDRAM debug harness — fast iteration (~6s build)
# Use 25 MHz to match what the full ulx3s design uses and stay within timing.
generate_verilog_cpu_sdram: rdl
	CLOCK_MHZ=25 $(MILL) fpga.ulx3s.soc.runMain soc.CpuSdramTestMain

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

# lint depends on .verilog_stamp (not generate_verilog) so it does not
# re-trigger the three Mill invocations when Verilog is already current.
lint: .verilog_stamp
	verilator --lint-only -Wall -Iout/hardware/borg/verilog --top-module tt_um_gonsolo_borg lint.vlt $$(cat out/hardware/borg/verilog/asic_files.txt | sed 's|^\.\./||')

test-chisel-core: rdl
	$(MILL) hardware.hutt.test

test-all:
	@MILL_JOBS=$(MILL_JOBS) python3 scripts/test_runner.py

datasheet.pdf: generate_verilog
	$(TT_TOOL) --create-pdf
user_config-sky130: export PDK=sky130A
user_config-sky130: generate_verilog
	$(TT_TOOL) --create-user-config --no-docker

user_config-ihp: export PDK=ihp-sg13g2
user_config-ihp: generate_verilog
	$(TT_TOOL) --create-user-config --ihp --no-docker

gds-sky130: user_config-sky130
	$(TT_TOOL) --harden --no-docker
gds-ihp: user_config-ihp
	$(TT_TOOL) --harden --ihp --no-docker

print_stats:
	./tt/tt_tool.py --print-stats
book:
	python3 docs/build_book.py
placement_animation:
	@echo "Rendering 100 placement frames (~15 min)..."
	bash scripts/animate_placement.sh

# --- SystemRDL → Chisel register generation ---
# systemrdl-compiler and peakrdl-cheader are provided by Nix (flake.nix).
# PeakRDL-chisel is a git submodule at repo root.
RDL_CHISEL   := $(CURDIR)/PeakRDL-chisel/src
RDL_DIR      := hardware/rdl
RDL_SRC      := $(wildcard $(RDL_DIR)/*.rdl)
RDL_SCALA_OUT:= hardware/borg/src/generated
export RDL_C_OUT := $(CURDIR)/out/hardware/borg/rdl
RDL_PYTHON   := PYTHONPATH=$(RDL_CHISEL):$$PYTHONPATH python3

rdl: $(RDL_SRC)
	@mkdir -p $(RDL_C_OUT)
	@$(RDL_PYTHON) $(RDL_DIR)/validate_rdl.py
	@$(RDL_PYTHON) $(RDL_DIR)/generate.py $(RDL_SCALA_OUT) $(RDL_C_OUT)
	@# _Static_assert is a C11 keyword — no header needed. Strip the assert.h
	@# include that PeakRDL-cheader emits so the generated file is self-contained.
	@sed -i '/#include <assert.h>/d; s/static_assert(/_Static_assert(/g' $(RDL_C_OUT)/borg_regs.h

clean:
	rm -f src/config_merged.json src/user_config.json .verilog_stamp .verilog_sim_stamp
	rm -rf $(RDL_C_OUT)
	rm -rf $(RDL_SCALA_OUT)
	rm -rf out/
	$(MAKE) -C fpga clean
	$(MAKE) -C test/soc clean
	$(MAKE) -C software clean
	$(MAKE) -C simulation clean

clean-gh-runs:
	gh run list --limit 200 --json databaseId --jq '.[8:] | .[].databaseId' | xargs -I {} gh run delete {}

.PHONY: all generate_verilog generate_verilog_sim generate_verilog_ulx3s help print_stats gds-sky130 gds-ihp user_config-sky130 user_config-ihp lint test-all clean rdl \
	test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl \
	test-cocotb-soc-core-gl test-cocotb-soc-borg-gl test-chisel-borg test-chisel-core \
	book clean-gh-runs scripts/test_summary.sh

