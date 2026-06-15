ROOT     := $(shell git rev-parse --show-toplevel)
RDL_OUT  := $(ROOT)/out/hardware/borg/rdl
CLANG    ?= clang++

.PHONY: all clean verilator arcilator test-sim-common

all: verilator arcilator

verilator:
	$(MAKE) -C verilator

arcilator:
	$(MAKE) -C arcilator

# Standalone unit tests for simulation/common (uart_decoder, flat_read16/flat_write16).
# No Verilator or Arcilator model required — pure C++ logic test.
test-sim-common: common/build
	$(CLANG) -O0 -g -std=c++17 \
	    -I common -I $(RDL_OUT) \
	    common/test_common.cpp common/uart_decoder.cpp \
	    -o common/build/test_common
	common/build/test_common

common/build:
	mkdir -p common/build

common/build/test_common: common/build common/test_common.cpp common/uart_decoder.cpp \
                          common/uart_decoder.h common/common_sim.h
	$(CLANG) -O0 -g -std=c++17 \
	    -I common -I $(RDL_OUT) \
	    common/test_common.cpp common/uart_decoder.cpp \
	    -o common/build/test_common

clean:
	$(MAKE) -C verilator clean
	$(MAKE) -C arcilator clean
	rm -rf common/build

