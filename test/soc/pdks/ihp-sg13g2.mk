# IHP SG13G2: IO lib + patched stdcell

IHP_IO_LIB  = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_io/verilog/sg13g2_io.v
IHP_STD_LIB = $(PDK_ROOT)/ihp-sg13g2/libs.ref/sg13g2_stdcell/verilog/sg13g2_stdcell.v

ifeq ($(wildcard $(IHP_IO_LIB)),)
    $(error GL Simulation error: PDK_ROOT invalid. Missing: $(IHP_IO_LIB))
endif

VERILOG_SOURCES    += $(IHP_IO_LIB)
PATCHED_STD_LIB     = sim_build/gl/patched_stdcell.v
VERILOG_SOURCES    += $(PATCHED_STD_LIB)

# Rule to patch stdcells
$(PATCHED_STD_LIB): $(IHP_STD_LIB)
	@mkdir -p $(dir $@)
	python3 $(CURDIR)/patch_stdcell.py $< $@

# Ensure patched lib is built before simulation
sim_build/gl/sim.vvp: $(PATCHED_STD_LIB)
