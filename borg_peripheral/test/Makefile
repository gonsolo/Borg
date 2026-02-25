SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog

SRC_DIR = $(PWD)/../src

YAML_SOURCES = $(shell sed -n '/source_files:/,/pinout:/p' ../info.yaml | grep '\- "' | sed 's/.*"\(.*\)".*/\1/')

ifneq ($(GATES),yes)

# RTL Simulation:
SIM_BUILD = sim_build/rtl
# Wir nehmen NUR die Dateien aus der info.yaml. 
# Da tt_wrapper.v dort drin steht, brauchen wir keine ADDITIONAL_SOURCES für den Wrapper mehr.
VERILOG_SOURCES += $(addprefix $(SRC_DIR)/, $(YAML_SOURCES))

else

# Gate Level Simulation:
SIM_BUILD = sim_build/gl
COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL -DSIM
VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v
VERILOG_SOURCES += $(PWD)/gate_level_netlist.v

endif

COMPILE_ARGS += -I$(SRC_DIR) -I$(SRC_DIR)/test_harness

VERILOG_SOURCES += $(PWD)/tb.v
TOPLEVEL = tb
COCOTB_TEST_MODULES = test

include $(shell cocotb-config --makefiles)/Makefile.sim
