SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog

SRC_DIR = $(PWD)/../src

YAML_SOURCES = AddRawFN.sv AddRecFN.sv Borg.sv RoundAnyRawFNToRecFN_ie8_is26_oe8_os24.sv RoundRawFNToRecFN_e8_s24.sv instructionMemory_4x32.sv registerFile_4x32.sv tt_wrapper.v test_harness/falling_edge_detector.sv test_harness/rising_edge_detector.sv test_harness/reclocking.sv test_harness/spi_reg.sv test_harness/synchronizer.sv

ifneq ($(GATES),yes)

SIM_BUILD = sim_build/rtl
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
