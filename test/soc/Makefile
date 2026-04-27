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

    COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL -DSIM

    # PDK defaults: prefer env var, then auto-detect from installed ciel versions
    PDK ?= sky130A
    ifeq ($(PDK_ROOT),)
        ifeq ($(findstring ihp,$(PDK)),ihp)
            PDK_ROOT := $(firstword $(wildcard $(HOME)/.ciel/ciel/ihp-sg13g2/versions/*/))
        else
            PDK_ROOT := $(firstword $(wildcard $(HOME)/.ciel/ciel/sky130A/versions/*/))
        endif
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
    ifeq ($(findstring sky130,$(PDK)),sky130)
        # Sky130A: primitives + standard cells (no patching needed)
        COMPILE_ARGS   += -gspecify
        SKY130_PRIM    = $(PDK_ROOT)/sky130A/libs.ref/sky130_fd_sc_hd/verilog/primitives.v
        SKY130_STD_LIB = $(PDK_ROOT)/sky130A/libs.ref/sky130_fd_sc_hd/verilog/sky130_fd_sc_hd.v
        ifeq ($(wildcard $(SKY130_PRIM)),)
            $(error GL Simulation error: PDK_ROOT invalid. Missing: $(SKY130_PRIM))
        endif
        VERILOG_SOURCES += $(SKY130_PRIM) $(SKY130_STD_LIB)
    else ifeq ($(findstring gf180,$(PDK)),gf180)
        # GF180MCU: standard cells (7-track 5V)
        COMPILE_ARGS   += -gspecify
        GF180_STD_LIB  = $(PDK_ROOT)/gf180mcuD/libs.ref/gf180mcu_fd_sc_mcu7t5v0/verilog/gf180mcu_fd_sc_mcu7t5v0.v
        ifeq ($(wildcard $(GF180_STD_LIB)),)
            $(error GL Simulation error: PDK_ROOT invalid. Missing: $(GF180_STD_LIB))
        endif
        VERILOG_SOURCES += $(GF180_STD_LIB)
    else
        # IHP SG13G2: IO lib + patched stdcell
        COMPILE_ARGS   += -gspecify
        IHP_IO_LIB  = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
        IHP_STD_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v
        ifeq ($(wildcard $(IHP_IO_LIB)),)
            $(error GL Simulation error: PDK_ROOT invalid. Missing: $(IHP_IO_LIB))
        endif
        VERILOG_SOURCES    += $(IHP_IO_LIB)
        PATCHED_STD_LIB     = sim_build/gl/patched_stdcell.v
        VERILOG_SOURCES    += $(PATCHED_STD_LIB)
    endif
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
	MODULE=user_peripherals.borg.test $(MAKE) sim

# Clean up build artifacts
clean::
	rm -rf sim_build results.xml tb.vcd tb.vcd.hier

