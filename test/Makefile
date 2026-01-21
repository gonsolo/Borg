# ==============================================================================
# Cocotb Test Makefile for ttihp-verilog
# ==============================================================================

# Simulation defaults
SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog
SRC_DIR = $(PWD)/../src

# Program defaults (for core tests)
PROG ?= hello
PROG_FILE ?= $(PROG).hex

# --- 1. AUTOMATIC SOURCE EXTRACTION ---
# Only extract RTL from info.yaml if we are NOT in Gate-Level mode.
# This prevents the "tt_um_tt_tinyQV already declared" error.
ifneq ($(GATES),yes)
    YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' ../info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/')
    VERILOG_SOURCES += $(addprefix $(SRC_DIR)/,$(YAML_SOURCES))
endif

# --- 2. TARGET SPECIFIC CONFIGURATION ---
ifeq ($(MAKECMDGOALS),core)
    # Core tests: Use QSPI testbench and flash models
    VERILOG_SOURCES += $(PWD)/sim_qspi.v $(PWD)/tb_qspi.v
    TOPLEVEL = tb_qspi
    COCOTB_TEST_MODULES = test
    COMPILE_ARGS += -DPROG_FILE=\"$(PROG_FILE)\"
else
    # Peripheral tests: Use the standard TinyTapeout testbench
    VERILOG_SOURCES += $(PWD)/tb.v
    TOPLEVEL = tb
    COCOTB_TEST_MODULES ?= test
endif

# --- 3. COMPILATION ARGUMENTS ---
COMPILE_ARGS += -I$(SRC_DIR) -DSIM
export PYTHONPATH := $(PWD):$(PYTHONPATH)

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

    # Note: We do NOT add -DUSE_POWER_PINS here because the error log showed
    # the netlist module ports do not include VPWR/VGND.

    # Include IHP SG13G2 Library Models
    VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
    VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v

    # Include the synthesized netlist
    VERILOG_SOURCES += $(PWD)/gate_level_netlist.v
endif

# --- 5. COCOTB MAKE RULES ---
include $(shell cocotb-config --makefiles)/Makefile.sim

# --- 6. HELPER TARGETS ---

# Target to run core tests: make core PROG=hello
core:
	$(MAKE)

# Target to run specific peripheral tests: make borg.test
%.test:
	MODULE=user_peripherals.$*.test $(MAKE)

# Debug target to verify source lists
debug_sources:
	@echo "Detected Source Files from info.yaml: $(YAML_SOURCES)"
	@echo "Final VERILOG_SOURCES list: $(VERILOG_SOURCES)"
