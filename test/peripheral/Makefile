SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog

SRC_DIR = $(PWD)/../../src

ifneq ($(GATES),yes)

SIM_BUILD = sim_build/rtl
HARNESS_DIR = $(PWD)/../../out/harness/verilog
VERILOG_SOURCES += $(wildcard $(HARNESS_DIR)/*.sv)

else

# Gate Level Simulation:
SIM_BUILD = sim_build/gl
COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL -DSIM
VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v
VERILOG_SOURCES += $(PWD)/gate_level_netlist.v

endif

COMPILE_ARGS += -I$(OUT_DIR) -I$(PWD)/../../peripheral/src/test_harness -I$(PWD)/../../peripheral/src

VERILOG_SOURCES += $(PWD)/tb.v
TOPLEVEL = tb
COCOTB_TEST_MODULES = test

include $(shell cocotb-config --makefiles)/Makefile.sim
