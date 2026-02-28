SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog

SRC_DIR = $(PWD)/../../src

ifneq ($(GATES),yes)

SIM_BUILD = sim_build/rtl
VERILOG_BORG_SOURCES += $(addprefix $(SRC_DIR)/user_peripherals/borg/, AddRawFN.sv AddRecFN.sv Borg.sv RoundAnyRawFNToRecFN_ie8_is26_oe8_os24.sv RoundRawFNToRecFN_e8_s24.sv instructionMemory_4x32.sv registerFile_4x32.sv)
VERILOG_HARNESS_SOURCES += $(PWD)/../../peripheral/src/tt_wrapper.v $(addprefix $(PWD)/../../peripheral/src/test_harness/, falling_edge_detector.sv rising_edge_detector.sv reclocking.sv spi_reg.sv synchronizer.sv)
VERILOG_SOURCES += $(VERILOG_BORG_SOURCES) $(VERILOG_HARNESS_SOURCES)

else

# Gate Level Simulation:
SIM_BUILD = sim_build/gl
COMPILE_ARGS += -DGL_TEST -DFUNCTIONAL -DSIM
VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
VERILOG_SOURCES += $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v
VERILOG_SOURCES += $(PWD)/gate_level_netlist.v

endif

COMPILE_ARGS += -I$(SRC_DIR)/user_peripherals/borg -I$(PWD)/../../peripheral/src/test_harness -I$(PWD)/../../peripheral/src

VERILOG_SOURCES += $(PWD)/tb.v
TOPLEVEL = tb
COCOTB_TEST_MODULES = test

include $(shell cocotb-config --makefiles)/Makefile.sim
