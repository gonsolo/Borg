SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog

SRC_DIR = $(CURDIR)/../../src

ifneq ($(GATES),yes)

SIM_BUILD = sim_build/rtl
HARNESS_DIR = $(CURDIR)/../../out/harness/verilog
VERILOG_SOURCES += $(wildcard $(HARNESS_DIR)/*.sv)

else

# Gate Level Simulation:
SIM_BUILD = sim_build/gl
COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL -DSIM

# Auto-detect PDK_ROOT from .nix-home if not set (for local pdk-ciel installations)
ifeq ($(PDK_ROOT),)
    PDK_ROOT := $(firstword $(wildcard $(HOME)/.ciel/ciel/ihp-sg13g2/versions/*/))
endif

# Check for PDK libraries
IHP_IO_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
IHP_STD_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v

ifeq ($(wildcard $(IHP_IO_LIB)),)
    $(error GL Simulation error: PDK_ROOT must be set and point to a valid IHP-SG13G2 PDK installation. File missing: $(IHP_IO_LIB))
endif

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

VERILOG_SOURCES += $(IHP_IO_LIB)
VERILOG_SOURCES += $(IHP_STD_LIB)
VERILOG_SOURCES += $(GL_NETLIST)

endif

COMPILE_ARGS += -I$(OUT_DIR) -I$(CURDIR)/../../peripheral/src/test_harness -I$(CURDIR)/../../peripheral/src

VERILOG_SOURCES += $(CURDIR)/tb.v
TOPLEVEL = tb
COCOTB_TEST_MODULES = test

include $(shell cocotb-config --makefiles)/Makefile.sim
