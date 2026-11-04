ROOT     := $(shell git rev-parse --show-toplevel)
RDL_OUT  := $(ROOT)/out/hardware/borg/rdl
HOSTCXX ?= g++

.PHONY: all clean direct verilator test-sim-common

all: direct verilator

verilator:
	$(MAKE) -C verilator

direct:
	$(MAKE) -C direct

# Standalone unit tests for simulation/common (uart_decoder).
# No Verilator or Arcilator model required — pure C++ logic test.
test-sim-common: common/build
	$(HOSTCXX) -O0 -g -std=c++17 \
	    -I common -I $(RDL_OUT) \
	    common/test_common.cpp common/uart_decoder.cpp \
	    -o common/build/test_common
	common/build/test_common

common/build:
	mkdir -p common/build

common/build/test_common: common/build common/test_common.cpp common/uart_decoder.cpp \
                          common/uart_decoder.h
	$(HOSTCXX) -O0 -g -std=c++17 \
	    -I common -I $(RDL_OUT) \
	    common/test_common.cpp common/uart_decoder.cpp \
	    -o common/build/test_common

clean:
	$(MAKE) -C verilator clean
	$(MAKE) -C direct clean
	rm -rf common/build

