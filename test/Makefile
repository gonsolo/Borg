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
# We only include RTL sources if we are NOT doing a Gate-Level simulation.
# This prevents the "module already declared" error in Icarus Verilog.
ifneq ($(GATES),yes)
    # Extracts the source file list from info.yaml to keep things in sync
    YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' ../info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/')
    VERILOG_SOURCES += $(addprefix $(SRC_DIR)/,$(YAML_SOURCES))
endif

# --- 2. TARGET SPECIFIC CONFIGURATION ---
# We swap the Testbench and Python module based on what we are testing
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
    # Default if no specific module is provided via %.test
    COCOTB_TEST_MODULES ?= test
endif

# --- 3. COMPILATION ARGUMENTS ---
COMPILE_ARGS += -I$(SRC_DIR) -DSIM
export PYTHONPATH := $(PWD):$(PYTHONPATH)

# Icarus Verilog specific flags (SystemVerilog support)
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
    # -DFUNCTIONAL bypasses timing checks that Icarus doesn't support
    # -DGL_TEST allows the testbench to handle power pins if necessary
    COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL

    # Include IHP SG13G2 Library Models
    VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
    VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v

    # Include the synthesized netlist (this replaces the RTL files)
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

# Debug target to check if YAML sources are being picked up correctly
debug_sources:
	@echo "Detected Source Files from info.yaml:"
	@echo "$(YAML_SOURCES)"
	@echo "Final VERILOG_SOURCES list:"
	@echo "$(VERILOG_SOURCES)"
