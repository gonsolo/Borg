# ==============================================================================
# Cocotb Test Makefile for ttihp-verilog (CI Compatible)
# ==============================================================================

# Simulation defaults
SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog
SRC_DIR = $(CURDIR)/../../src

# --- 1. AUTOMATIC SOURCE EXTRACTION ---
ifneq ($(GATES),yes)
    YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' ../../info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/')
    VERILOG_SOURCES += $(addprefix $(SRC_DIR)/,$(YAML_SOURCES))
endif

# --- 2. TESTBENCH CONFIGURATION ---
VERILOG_SOURCES += $(CURDIR)/tb.v
TOPLEVEL = tb
COCOTB_TEST_MODULES ?= test,user_peripherals.borg.test

all: sim

# --- 3. COMPILATION ARGUMENTS ---
COMPILE_ARGS += -I$(SRC_DIR) -I$(CURDIR)/../../out/borg/verilog -DSIM
export PYTHONPATH := $(CURDIR):../../:$(PYTHONPATH)

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

    COMPILE_ARGS += -DGL_TEST -gspecify -DFUNCTIONAL

    # PDK Detection: Use environment (CI) or local fallback
    ifeq ($(PDK_ROOT),)
        PDK_ROOT := $(firstword $(wildcard $(HOME)/.ciel/ciel/ihp-sg13g2/versions/*/))
    endif

    IHP_IO_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
    IHP_STD_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v

    # Standard check
    ifeq ($(wildcard $(IHP_IO_LIB)),)
        $(error GL Simulation error: PDK_ROOT invalid. Missing: $(IHP_IO_LIB))
    endif

    # Libraries
    VERILOG_SOURCES += $(IHP_IO_LIB)
    PATCHED_STD_LIB = sim_build/gl/patched_stdcell.v
    VERILOG_SOURCES += $(PATCHED_STD_LIB)

    # --- CI-AWARE NETLIST DETECTION ---
    # The GH Action copies the netlist to ../gate_level_netlist.v
    CI_NETLIST = $(CURDIR)/../gate_level_netlist.v
    LOCAL_NETLIST = $(CURDIR)/../../runs/wokwi/final/nl/tt_um_tt_tinyQV.nl.v

    ifneq ($(wildcard $(CI_NETLIST)),)
        GL_NETLIST = $(CI_NETLIST)
    else
        GL_NETLIST = $(LOCAL_NETLIST)
    endif

    ifeq ($(wildcard $(GL_NETLIST)),)
        $(error GL Simulation error: Netlist not found at $(CI_NETLIST) or $(LOCAL_NETLIST))
    endif

    VERILOG_SOURCES += $(GL_NETLIST)
endif

# --- 5. COCOTB MAKE RULES ---
include $(shell cocotb-config --makefiles)/Makefile.sim

# Rule to patch stdcells
$(PATCHED_STD_LIB): $(IHP_STD_LIB)
	@mkdir -p $(dir $@)
	python3 $(CURDIR)/patch_stdcell.py $< $@

# Ensure patched lib is built before simulation
sim_build/gl/sim.vvp: $(PATCHED_STD_LIB)

# --- 6. HELPER TARGETS ---
debug_sources:
	@echo "Netlist being used: $(GL_NETLIST)"
	@echo "PDK_ROOT: $(PDK_ROOT)"

# Run the default simulation
core:
	$(MAKE) sim

# Run the borg specific test
borg:
	COCOTB_TEST_MODULES=user_peripherals.borg.test $(MAKE) sim

# Clean up build artifacts
clean::
	rm -rf sim_build results.xml

