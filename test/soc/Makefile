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
MODULE ?= test

all: sim

# --- 3. COMPILATION ARGUMENTS ---
COMPILE_ARGS += -I$(SRC_DIR) -I$(CURDIR)/../../out/hardware/borg/verilog -DSIM
export PYTHONPATH := $(CURDIR):../../out/hardware/borg/rdl:../../:$(PYTHONPATH)

# Icarus Verilog specific flags
ifeq ($(SIM), icarus)
    COMPILE_ARGS += -g2012
endif

# --- 4. RTL VS GATE LEVEL SETUP ---
ifneq ($(GATES),yes)
    # RTL simulation
    SIM_BUILD = sim_build/rtl
else
    # Gate level simulation — PDK-aware (Sky130 or IHP)
    SIM_BUILD = sim_build/gl

    COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL -DSIM -DUSE_POWER_PINS -DUNIT_DELAY=#1 -gspecify

    # PDK defaults: prefer env var, then auto-detect from installed ciel versions
    PDK ?= sky130A
    
    # Normalize PDK name
    ifeq ($(findstring ihp,$(PDK)),ihp)
        NORMALIZED_PDK := ihp-sg13g2
    else ifeq ($(findstring gf180,$(PDK)),gf180)
        NORMALIZED_PDK := gf180mcuD
    else
        NORMALIZED_PDK := sky130A
    endif

    # Detect PDK_ROOT from installed ciel versions
    ifeq ($(PDK_ROOT),)
        PDK_ROOT := $(firstword $(wildcard $(HOME)/.ciel/ciel/$(NORMALIZED_PDK)/versions/*/))
    endif

    # --- CI-AWARE NETLIST DETECTION ---
    # The GH Action copies the netlist to ../gate_level_netlist.v
    CI_NETLIST = $(CURDIR)/../gate_level_netlist.v
    LOCAL_NETLIST = $(CURDIR)/../../runs/wokwi/final/nl/tt_um_gonsolo_borg.nl.v

    ifneq ($(wildcard $(CI_NETLIST)),)
        GL_NETLIST = $(CI_NETLIST)
    else
        GL_NETLIST = $(LOCAL_NETLIST)
    endif

    ifeq ($(wildcard $(GL_NETLIST)),)
        $(error GL Simulation error: Netlist not found at $(CI_NETLIST) or $(LOCAL_NETLIST))
    endif

    VERILOG_SOURCES += $(GL_NETLIST)

    # --- PDK-SPECIFIC LIBRARY SELECTION ---
    include $(CURDIR)/pdks/$(NORMALIZED_PDK).mk
endif

# --- 5. COCOTB MAKE RULES ---
include $(shell cocotb-config --makefiles)/Makefile.sim



# --- 6. HELPER TARGETS ---
debug_sources:
	@echo "Netlist being used: $(GL_NETLIST)"
	@echo "PDK_ROOT: $(PDK_ROOT)"

# Run the default simulation
core:
	$(MAKE) sim

# Run the borg specific test
borg:
	MODULE=user_peripherals.borg.test $(MAKE) sim

# Clean up build artifacts
clean::
	rm -rf sim_build results.xml tb.vcd tb.vcd.hier

