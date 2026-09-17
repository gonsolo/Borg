TT_TOOL   := ./tt/tt_tool.py
# PYTHONPATH=$$COCOTB_PYTHONPATH (not the ambient shell PYTHONPATH): nix's
# devShell aggregates PYTHONPATH from every python.withPackages input
# regardless of interpreter version, so plain $$PYTHONPATH here is a mix
# of cocotbForTests' python3.13 packages and pythonEnv's python3.14 ones --
# cocotb's own numpy import then silently resolves to whichever copy lands
# first. COCOTB_PYTHONPATH (flake.nix's shellHook) is cocotbForTests' own
# site-packages only.
TEST_SOC  := env PYTHONPATH=$$COCOTB_PYTHONPATH make -C test/soc -B
MILL_JOBS := $(if $(CI),1,12)
MILL_OPTS := $(if $(CI),--no-server,) -j $(MILL_JOBS)
MILL      := mill $(MILL_OPTS)



BOLD := \033[1m
RESET   := \033[0m

all: help
help:
	@echo "commands: "
	@echo -e "$(BOLD)  librelane:\t\t\tFull wafer.space signoff (GF180MCU, 1x1 slot) -- THE tapeout flow.$(RESET)"
	@echo -e "  gds-sky130:\t\t\tGenerate Sky130 GDS II file for Tinytapeout (legacy)."
	@echo -e "  gds-ihp:\t\t\tGenerate IHP SG13G2 GDS II file for Tinytapeout (legacy)."
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
	@echo -e "  build-vkcube:\t\t\tBuild Vulkan-Tools vkcube host binary."
	@echo -e "  vulkan-cts:\t\t\tRun the Vulkan CTS survivor slice against borgvk."
	@echo -e "  datasheet.pdf:\t\tGenerate datasheet for Tinytapeout."
	@echo -e "  user_config-*:\t\tGenerate user config for tapeout."
	@echo -e "  print_stats:\t\t\tPrint statistics about tile usage."
	@echo -e "  book:\t\t\t\tBuild the documentation book."
	@echo -e "  clean:\t\t\tRemove all build artifacts."
	@echo -e "  clean-gh-runs:\t\tClean up GitHub workflow runs."
	@echo -e "  opensbi:\t\t\tBuild OpenSBI FW_PAYLOAD for Borg (requires ext/opensbi + linux Image)."
	@echo -e "  linux:\t\t\tBuild Linux kernel for Borg (requires ext/linux)."
	@echo -e "  flash-linux:\t\t\tWrap + flash OpenSBI+Linux payload to SPI flash @ 0x400000."

# CLOCK_MHZ is an RTL elaboration parameter for SoC peripherals (the UART baud
# divisor, mainly), read by the SoC emitters: Tiny Tapeout SoC (TTMain, 4), the
# simulation SoC (BorgSimMain, 4), ULX3S (25 SoC / 125 HDMI). Override via env:
# CLOCK_MHZ=50 make generate_verilog. It is NOT the wafer.space tapeout clock:
# BorgOnlyTop has no such parameter; its clock is the signoff constraint
# CLOCK_PERIOD in asic/wafer.space/librelane/config.yaml (125 ns = 8 MHz).

HAND_CHISEL = $(shell find hardware/borg/src hardware/soc/src hardware/hutt/src hardware/memory/src \
                        fpga/ulx3s/soc/src asic/tt/src asic/wafer/src \
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

# wafer.space Borg-only bridge target (BorgOnlyTop): same two post-steps as
# .verilog_stamp above and for the same reasons -- feeds the same yosys-based
# LibreLane synthesis flow, just a different (link-behind) top module, into
# out/hardware/borg/verilog_wafer/ rather than .../verilog/ (which this
# target must NOT touch -- TTMain owns that dir and wipes it on every run).
.verilog_wafer_stamp: $(HAND_CHISEL) $(RDL_SRC) | rdl
	$(MILL) asic.wafer.runMain asic.wafer.BorgOnlyMain
	@python3 scripts/init_bram_zero.py out/hardware/borg/verilog_wafer
	@sed -i 's|// synthesis translate_on\t.*|// synthesis translate_on|g' out/hardware/borg/verilog_wafer/*.sv
	@touch $@

generate_verilog_wafer: .verilog_wafer_stamp

# Same design on the wafer.space 1x1 slot -- 40 bidir + 12 input-only pads
# instead of 46 + 4, so BorgOnlyTop uses a different lane map (see its class
# doc; it is a re-map, not a truncation).  Emitted to its own directory
# because both slots produce a module named BorgOnlyTop with different port
# widths -- mixing them in one directory would be silently wrong.
.verilog_wafer_1x1_stamp: $(HAND_CHISEL) $(RDL_SRC) | rdl
	$(MILL) asic.wafer.runMain asic.wafer.BorgOnly1x1Main
	@python3 scripts/init_bram_zero.py out/hardware/borg/verilog_wafer_1x1
	@sed -i 's|// synthesis translate_on\t.*|// synthesis translate_on|g' out/hardware/borg/verilog_wafer_1x1/*.sv
	@touch $@

generate_verilog_wafer_1x1: .verilog_wafer_1x1_stamp

# Verilator simulation Verilog — flat MemBackendIO top (no QSPI), into
# out/hardware/borg/verilog_sim/.  Used by simulation/verilator.
.verilog_sim_stamp: $(HAND_CHISEL) $(RDL_SRC) | rdl
	CLOCK_MHZ=4 $(MILL) hardware.soc.runMain soc.BorgSimMain
	@touch $@

generate_verilog_sim: .verilog_sim_stamp

# ULX3S (ECP5-85K) Verilog emission stub — no synthesis flow yet (Step 27).
# ULX3S SoC clock (MHz). Single source of truth is ULX3S_MHZ in
# fpga/ulx3s/Makefile, which passes it here as ULX3S_CLOCK_MHZ.
ULX3S_CLOCK_MHZ ?= 25
generate_verilog_ulx3s: rdl
	CLOCK_MHZ=$(ULX3S_CLOCK_MHZ) $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SMain

# Rung A of the wafer.space Borg-only bridge's on-hardware ladder: same full
# SoC as generate_verilog_ulx3s, but borgMode=BorgLoopback -- Borg is reached
# only through the real link RTL (BorgLinkMaster+BorgLinkSlave, "pins" are
# internal wires), not directly. Separate output dir/emitter so the default
# demo target above is completely unaffected.
generate_verilog_ulx3s_loopback: rdl
	CLOCK_MHZ=$(ULX3S_CLOCK_MHZ) $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SLoopbackMain

# Rungs B/C of the same ladder: borgMode=BorgExternal -- the link reaches real
# GP/GN pins (rung B: ribbon-cable loopback on this board; rung C: a second
# ULX3S running BorgOnlyTop) instead of BorgLoopback's internal wires.
generate_verilog_ulx3s_external: rdl
	CLOCK_MHZ=$(ULX3S_CLOCK_MHZ) $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SExternalMain

# Rung B: master + slave + Borg all on the FPGA, but the master<->slave path
# routed out to J1/J2 pads and shorted back by a ribbon (narrow w=8 -- see
# BorgMode's doc for why w=16 does not fit).
generate_verilog_ulx3s_padloop: rdl
	CLOCK_MHZ=$(ULX3S_CLOCK_MHZ) $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SPadLoopMain

# Minimal ULX3S Verilog — Hutt + UART only, no Borg.  Fast-iteration target.
generate_verilog_ulx3s_minimal:
	CLOCK_MHZ=25 $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SMinimalMain

# Minimal ULX3S Verilog, RV64 + CLINT — Hutt without Borg, for isolating
# whether a Linux/OpenSBI boot that's silent on real hardware (despite
# working in simulation) is a full-SoC timing-closure issue rather than a
# logic bug.
generate_verilog_ulx3s_minimal_linux:
	CLOCK_MHZ=25 $(MILL) fpga.ulx3s.soc.runMain soc.ULX3SMinimalLinuxMain

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

# Lint the RTL that is actually taped out: BorgOnlyTop on the wafer.space 1x1
# slot. `lint` above checks the retired Tiny Tapeout SoC top, which contains no
# link bridge at all, so this is the only lint of the tapeout design.
# Not part of test-all yet: on 2026-09-17 it reports four UNUSEDSIGNAL warnings.
# One is a real FP32 bug -- BorgLinkSlave keeps only gpuMem.wdata[15:0] (a 16-bit
# design from the FP16 era) while the geometry sequencer's setup store and the
# core's STORE now write 32-bit words, so on silicon their upper halves are lost.
# The other three (unused input side of output pads, wlen bit 0, LinkRx flitAcc's
# narrow-mode byte) look like design facts to waive once that is fixed.
lint-wafer: .verilog_wafer_1x1_stamp
	verilator --lint-only -Wall -Iout/hardware/borg/verilog_wafer_1x1 --top-module BorgOnlyTop1x1 lint.vlt $$(sed 's|^\.\./||' out/hardware/borg/verilog_wafer_1x1/wafer_files.txt)

test-chisel-core: rdl
	$(MILL) hardware.hutt.test

test-all:
	@MILL_JOBS=$(MILL_JOBS) python3 scripts/test_runner.py

build-vkcube: Vulkan-Tools/build/cube/vkcube

Vulkan-Tools/build/cube/vkcube:
	env -u CC -u CXX cmake -S Vulkan-Tools -B Vulkan-Tools/build \
		-DCMAKE_BUILD_TYPE=Release \
		-DBUILD_CUBE=ON \
		-DBUILD_VULKANINFO=OFF \
		-DBUILD_ICD=OFF
	env -u CC -u CXX cmake --build Vulkan-Tools/build --target vkcube -j$$(nproc)

# Run the Vulkan CTS (dEQP-VK) "survivor" slice against the borgvk driver and
# report "passed N of <mandatory total>".  Requires a built deqp-vk (see
# docs/03_software_driver.md) and the borgvk ICD (make -C software/mesa).
# Override the CTS checkout with VK_GL_CTS=/path/to/VK-GL-CTS.
vulkan-cts:
	@bash scripts/run_vulkan_cts.sh

datasheet.pdf: generate_verilog
	$(TT_TOOL) --create-pdf
user_config-sky130: export PDK=sky130A
user_config-sky130: generate_verilog
	$(TT_TOOL) --create-user-config --no-docker

user_config-ihp: export PDK=ihp-sg13g2
user_config-ihp: generate_verilog
	$(TT_TOOL) --create-user-config --ihp --no-docker

# The tapeout flow. wafer.space, GF180MCU, 1x1 slot, full signoff (DRC, LVS,
# antenna, multi-corner STA) -- the Tiny Tapeout gds-* targets below are legacy.
# Everything slot/PDK-specific lives in asic/wafer.space/Makefile; this is the
# one entry point so nobody has to remember which sub-Makefile and which SLOT.
# Takes hours: run it under `systemd-run --user` (see asic/wafer.space/Makefile).
librelane:
	$(MAKE) -C asic/wafer.space SLOT=1x1 librelane

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
# python3-borg-rdl: pythonEnv's python3 (systemrdl-compiler etc.), shimmed
# by flake.nix's shellHook under this name -- bare `python3` on PATH can
# resolve to a different nativeBuildInput's bundled interpreter instead.
RDL_PYTHON   := PYTHONPATH=$(RDL_CHISEL):$$PYTHONPATH python3-borg-rdl

rdl: $(RDL_SRC)
	@mkdir -p $(RDL_C_OUT)
	@$(RDL_PYTHON) $(RDL_DIR)/validate_rdl.py
	@$(RDL_PYTHON) $(RDL_DIR)/generate.py $(RDL_SCALA_OUT) $(RDL_C_OUT)
	@# _Static_assert is a C11 keyword — no header needed. Strip the assert.h
	@# include that PeakRDL-cheader emits so the generated file is self-contained.
	@sed -i '/#include <assert.h>/d; s/static_assert(/_Static_assert(/g' $(RDL_C_OUT)/borg_regs.h

clean:
	rm -f src/config_merged.json src/user_config.json .verilog_stamp .verilog_sim_stamp .verilog_wafer_stamp
	rm -rf $(RDL_C_OUT)
	rm -rf $(RDL_SCALA_OUT)
	rm -rf out/
	$(MAKE) -C fpga clean
	$(MAKE) -C test/soc clean
	$(MAKE) -C software clean
	$(MAKE) -C simulation clean

clean-gh-runs:
	gh run list --limit 200 --json databaseId --jq '.[8:] | .[].databaseId' | xargs -I {} gh run delete {}

# ---------------------------------------------------------------------------
# Linux-on-Borg targets — delegate to software/Makefile.
# Upstream source trees are expected at ext/opensbi and ext/linux.
# Override with OPENSBI_SRC=/path and LINUX_SRC=/path.
# ---------------------------------------------------------------------------
opensbi:
	$(MAKE) -C software opensbi

linux:
	$(MAKE) -C software linux

flash-linux:
	$(MAKE) -C software flash-linux

.PHONY: all generate_verilog generate_verilog_sim generate_verilog_ulx3s generate_verilog_ulx3s_loopback generate_verilog_ulx3s_external generate_verilog_ulx3s_padloop generate_verilog_wafer generate_verilog_wafer_1x1 librelane lint-wafer help print_stats gds-sky130 gds-ihp user_config-sky130 user_config-ihp lint test-all clean rdl \
	test-cocotb-soc-core-rtl test-cocotb-soc-borg-rtl \
	test-cocotb-soc-core-gl test-cocotb-soc-borg-gl test-chisel-borg test-chisel-core \
	book clean-gh-runs scripts/test_summary.sh vulkan-cts build-vkcube \
	opensbi linux flash-linux

