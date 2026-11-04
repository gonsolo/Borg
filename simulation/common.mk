# simulation/common.mk — variables shared by the simulator Makefiles.

ROOT         := $(shell git rev-parse --show-toplevel)
RDL_C_OUT    ?= $(ROOT)/out/hardware/borg/rdl
FIRMWARE_DIR ?= $(ROOT)/software/borg

# Chisel + RDL sources: a change re-emits the model.
CHISEL_SRCS = $(shell find $(ROOT)/hardware/borg/src $(ROOT)/hardware/soc/src \
                           $(ROOT)/hardware/hutt/src $(ROOT)/hardware/riscv/src $(ROOT)/hardware/memory/src \
                           -name '*.scala' -not -path '*/generated/*' 2>/dev/null) \
              $(wildcard $(ROOT)/hardware/rdl/*.rdl)

# Host compilers for the simulators (the shell's CC/CXX are the riscv cross compilers).
HOSTCXX ?= g++
HOSTCC ?= gcc
