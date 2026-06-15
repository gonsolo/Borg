# simulation/common.mk — variables shared by arcilator/ and verilator/ Makefiles.
# Include from each backend's Makefile with:
#   include ../common.mk

ROOT         := $(shell git rev-parse --show-toplevel)
RDL_C_OUT    ?= $(ROOT)/out/hardware/borg/rdl
FIRMWARE_DIR ?= $(ROOT)/software/borg

# Headers whose content is baked into the harness binaries; a change to any of
# these must trigger a harness rebuild.  Wildcard all firmware headers so that
# additions are picked up automatically.
LAYOUT_HDRS = ../common/common_sim.h $(wildcard $(FIRMWARE_DIR)/*.h)

# Chisel + RDL sources — used by arcilator to decide when to re-emit FIRRTL.
CHISEL_SRCS = $(shell find $(ROOT)/hardware/borg/src $(ROOT)/hardware/soc/src \
                           $(ROOT)/hardware/hutt/src $(ROOT)/hardware/memory/src \
                           $(ROOT)/asic/tt/src \
                           -name '*.scala' -not -path '*/generated/*' 2>/dev/null) \
              $(wildcard $(ROOT)/hardware/rdl/*.rdl)

# Firmware C/H/ASM sources — used to trigger firmware .bin rebuilds.
FW_SRCS = $(shell find $(FIRMWARE_DIR) $(ROOT)/software/hutt \
                       -maxdepth 1 \( -name '*.c' -o -name '*.h' -o -name '*.s' \) 2>/dev/null) \
          $(ROOT)/software/hutt/memmap

# Compiler used for harness object files (overridable per backend).
CLANG ?= clang++

# Flags shared by every harness object compilation.
CFLAGS_COMMON = -O3 -I../common -I$(RDL_C_OUT)
