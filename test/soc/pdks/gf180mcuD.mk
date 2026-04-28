# GF180MCU: standard cells (7-track 5V)

GF180_STD_LIB  = $(PDK_ROOT)/gf180mcuD/libs.ref/gf180mcu_fd_sc_mcu7t5v0/verilog/gf180mcu_fd_sc_mcu7t5v0.v

ifeq ($(wildcard $(GF180_STD_LIB)),)
    $(error GL Simulation error: PDK_ROOT invalid. Missing: $(GF180_STD_LIB))
endif

VERILOG_SOURCES += $(GF180_STD_LIB)
COMPILE_ARGS += -DUSE_POWER_PINS -DUNIT_DELAY=\#1 -gspecify
