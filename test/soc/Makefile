# ==============================================================================
# Cocotb Test Makefile for ttihp-verilog
# ==============================================================================

# Simulation defaults
SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog
SRC_DIR = $(PWD)/../../src



# --- 1. AUTOMATIC SOURCE EXTRACTION ---
# Only extract RTL from info.yaml if we are NOT in Gate-Level mode.
# This prevents the "tt_um_tt_tinyQV already declared" error.
ifneq ($(GATES),yes)
    YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' ../../info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/')
    VERILOG_SOURCES += $(addprefix $(SRC_DIR)/,$(YAML_SOURCES))
endif

# --- 2. TESTBENCH CONFIGURATION ---
VERILOG_SOURCES += $(PWD)/tb.v
TOPLEVEL = tb
COCOTB_TEST_MODULES ?= test

# --- 3. COMPILATION ARGUMENTS ---
COMPILE_ARGS += -I$(SRC_DIR) -I$(PWD)/../../out/borg/verilog -DSIM
export PYTHONPATH := $(PWD):../../:$(PYTHONPATH)

# Icarus Verilog specific flags
ifeq ($(SIM), icarus)
    COMPILE_ARGS += -g2012
endif

# --- 4. RTL VS GATE LEVEL SETUP ---
ifneq ($(GATES),yes)
    # RTL simulation
    SIM_BUILD = sim_build/rtl
else
    # Gate level simulation (IHP PDK)
    SIM_BUILD = sim_build/gl

    # GL_TEST: Used in tb.v for logic/power wiring
    # FUNCTIONAL: Required for IHP models to bypass unsupported timing syntax in Icarus
    COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL

    # Check for PDK libraries
    # Auto-detect PDK_ROOT from .nix-home if not set (for local pdk-ciel installations)
    ifeq ($(PDK_ROOT),)
        PDK_ROOT := $(firstword $(wildcard $(HOME)/.ciel/ciel/ihp-sg13g2/versions/*/))
    endif
    IHP_IO_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
    IHP_STD_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v

    ifeq ($(wildcard $(IHP_IO_LIB)),)
        $(error GL Simulation error: PDK_ROOT must be set and point to a valid IHP-SG13G2 PDK installation. File missing: $(IHP_IO_LIB))
    endif

    # Include IHP SG13G2 Library Models
    VERILOG_SOURCES += $(IHP_IO_LIB)
    VERILOG_SOURCES += $(IHP_STD_LIB)

    # Search for the gate-level netlist in multiple possible locations
    GL_NETLIST = $(firstword $(wildcard \
        $(CURDIR)/gate_level_netlist.v \
        $(CURDIR)/../gate_level_netlist.v \
        $(CURDIR)/../../runs/wokwi/final/nl/*.v \
        $(CURDIR)/../../tt_submission/*.v \
    ))

    ifeq ($(GL_NETLIST),)
        $(error GL Simulation error: Gate-level netlist not found. Run 'make gds' first or provide 'gate_level_netlist.v' in $(CURDIR)/..)
    endif

    VERILOG_SOURCES += $(GL_NETLIST)
endif

# --- 5. COCOTB MAKE RULES ---
include $(shell cocotb-config --makefiles)/Makefile.sim

# --- 6. HELPER TARGETS ---

# Explicit targets for SoC integration
core:
	$(MAKE)

borg:
	COCOTB_TEST_MODULES=user_peripherals.borg.test $(MAKE)



# Target to run specific peripheral tests: make borg.test
%.test:
	MODULE=user_peripherals.$*.test $(MAKE)

# Debug target to verify source lists
debug_sources:
	@echo "Detected Source Files from info.yaml: $(YAML_SOURCES)"
	@echo "Final VERILOG_SOURCES list: $(VERILOG_SOURCES)"
