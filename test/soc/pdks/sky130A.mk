# Sky130A: primitives + standard cells (no patching needed)

SKY130_PRIM    = $(PDK_ROOT)/sky130A/libs.ref/sky130_fd_sc_hd/verilog/primitives.v
SKY130_STD_LIB = $(PDK_ROOT)/sky130A/libs.ref/sky130_fd_sc_hd/verilog/sky130_fd_sc_hd.v

ifeq ($(wildcard $(SKY130_PRIM)),)
    $(error GL Simulation error: PDK_ROOT invalid. Missing: $(SKY130_PRIM))
endif

VERILOG_SOURCES += $(SKY130_PRIM) $(SKY130_STD_LIB)
