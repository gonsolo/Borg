# defaults
SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog
SRC_DIR = $(PWD)/../src

# --- AUTOMATIC SOURCE EXTRACTION ---
# This parses info.yaml to get the source_files list automatically
YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' ../info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/')
VERILOG_SOURCES += $(addprefix $(SRC_DIR)/,$(YAML_SOURCES))

# SystemVerilog support for Icarus
ifeq ($(SIM), icarus)
    COMPILE_ARGS += -g2012
endif

# --- BORG SPECIFIC SETUP ---
export PYTHONPATH := $(PWD):$(PYTHONPATH)
# ---------------------------

ifneq ($(GATES),yes)
    # RTL simulation:
    SIM_BUILD = sim_build/rtl
    # PROJECT_SOURCES is now handled by the YAML_SOURCES logic above
else
    # Gate level simulation:
    SIM_BUILD = sim_build/gl
    COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL -DSIM
    VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
    VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v
    VERILOG_SOURCES += $(PWD)/gate_level_netlist.v
endif

COMPILE_ARGS += -I$(SRC_DIR)
VERILOG_SOURCES += $(PWD)/tb.v
TOPLEVEL = tb

include $(shell cocotb-config --makefiles)/Makefile.sim

# This maps 'make something.test' to a Cocotb run of that specific module
%.test:
	MODULE=user_peripherals.$*.test $(MAKE)
