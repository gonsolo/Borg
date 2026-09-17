# ==============================================================================
# cocotb tests for the QSPI CPU SoC (hardware/soc/src/QspiSocTop.scala):
# Hutt -> QSPI -> MemoryController -> Borg, end to end, at RTL.
# ==============================================================================

# Simulation defaults
SIM ?= icarus
FST ?= -fst
TOPLEVEL_LANG ?= verilog
ROOT = $(CURDIR)/../..

# --- 1. SOURCES ---
# QspiSocMain's own file list (paths prefixed "../", relative to out/..).
SOC_FILES = $(ROOT)/out/hardware/borg/verilog/soc_files.txt
VERILOG_SOURCES += $(addprefix $(ROOT)/,$(shell sed 's|^\.\./||' $(SOC_FILES) 2>/dev/null))

# --- 2. TESTBENCH CONFIGURATION ---
VERILOG_SOURCES += $(CURDIR)/tb.v
TOPLEVEL = tb
MODULE ?= test

all: sim

# --- 3. COMPILATION ARGUMENTS ---
# -DENABLE_INITIAL_MEM_ makes firtool's $readmemh ROM initializers (the Borg FP16
# LUTs: rcp/frsq/srgb) compile in — without it the LUT memories stay X and FRCP/
# FRSQ/FSRGB read back undefined.  The hex files are staged into this dir by the
# `luts` target (relative-path $readmemh resolves against the vvp CWD).  Matches
# the verilator/ulx3s flows, which all define this macro.
COMPILE_ARGS += -I$(ROOT)/out/hardware/borg/verilog -DSIM -DENABLE_INITIAL_MEM_
export PYTHONPATH := $(CURDIR):../../out/hardware/borg/rdl:../../:$(PYTHONPATH)

# Icarus Verilog specific flags
ifeq ($(SIM), icarus)
    COMPILE_ARGS += -g2012
endif

SIM_BUILD = sim_build/rtl

# --- 5. COCOTB MAKE RULES ---
include $(shell cocotb-config --makefiles)/Makefile.sim



# --- LUT hex initialization ---
# The generated Verilog initializes the Borg FP16 LUT ROMs with relative-path
# $readmemh("rcp_lut.hex"/"frsq_lut.hex"/"srgb_lut.hex").  Icarus resolves those
# against the process CWD (this directory), so the canonical copies in
# hardware/borg/src must be staged here before vvp runs — otherwise the LUTs are
# X and FRCP/FRSQ/FSRGB read back as undefined bits.
BORG_SRC = $(CURDIR)/../../hardware/borg/src
LUT_HEX  = rcp_lut.hex frsq_lut.hex srgb_lut.hex

$(LUT_HEX): %.hex: $(BORG_SRC)/%.hex
	cp $< $@

luts: $(LUT_HEX)

# Run the default simulation
core: luts
	$(MAKE) sim

# Run the borg specific test
borg: luts
	MODULE=user_peripherals.borg.test $(MAKE) sim

# Run real compiled RV32 firmware (software/borg/kernel.bin) through the real
# QSPI flash-read protocol (Python flash server in test_rv32_boot.py, built on
# test_util.py's proven start_read/send_instr primitives -- NOT sim_qspi_pmod,
# which turned out to have multiple bugs of its own and was abandoned) --
# verifies the RV32 firmware build actually boots, not just compiles.
rv32boot: luts
	MODULE=test_rv32_boot $(MAKE) sim

# Clean up build artifacts
clean::
	rm -rf sim_build results.xml tb.vcd tb.vcd.hier $(LUT_HEX)

