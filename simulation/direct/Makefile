# Direct simulator: Borg alone (no CPU, no firmware, no UART), driven by a C++ host.
# Model flow is the same as simulation/arcilator: FIRRTL -> arcilator -> arc.o + arc.h.
include ../common.mk

ARCILATOR = arcilator
FIRTOOL   = firtool
LLC       = llc
ARCDIR    = ../arcilator

export HARDENING_DISABLE=all

.PHONY: all clean
all: direct_sim

$(ROOT)/out/hardware/borg/firrtl_direct/BorgDirectSimTop.fir: $(CHISEL_SRCS)
	cd $(ROOT) && mill hardware.soc.runMain soc.BorgDirectSimMain

borg.mlir: $(ROOT)/out/hardware/borg/firrtl_direct/BorgDirectSimTop.fir
	$(FIRTOOL) $< --disable-layers=Verification --ir-hw > $@

arc.ll state.json &: borg.mlir
	$(ARCILATOR) $< --inline --observe-memories --state-file state.json -o arc.ll

arc.o: arc.ll
	$(LLC) -O3 -relocation-model=pic --filetype=obj $< -o $@

arc.h: state.json
	python3 $(ARCDIR)/arcilator-header-cpp.py $< > $@

SRCS = main.cpp
OBJS = $(SRCS:.cpp=.o) borg_spirb.o borg_core.o
# arc.h defines its layout statics non-inline, so exactly one translation unit may
# include it: main.cpp pulls in driver.cpp (unity build).
HDRS = direct_sim.h driver.h driver.cpp arc.h $(wildcard $(FIRMWARE_DIR)/*.h) $(RDL_C_OUT)/borg_regs.h

%.o: %.cpp $(HDRS)
	$(HOSTCXX) -std=c++17 -O3 -I. -I$(ARCDIR) -I../common -I$(RDL_C_OUT) -I$(FIRMWARE_DIR) -c $< -o $@

# The shared driver core, built for the host (register / DRAM access via bh_* in driver.cpp).
borg_core.o: $(FIRMWARE_DIR)/borg_core.c $(FIRMWARE_DIR)/borg_core.h $(wildcard $(FIRMWARE_DIR)/*.h) $(RDL_C_OUT)/borg_regs.h
	$(HOSTCC) -O2 -DBORG_HOST -I$(FIRMWARE_DIR) -I$(RDL_C_OUT) -c $< -o $@

borg_spirb.o: $(FIRMWARE_DIR)/borg_spirb.c $(FIRMWARE_DIR)/borg_spirb.h
	$(HOSTCC) -O2 -I$(FIRMWARE_DIR) -c $< -o $@

direct_sim: $(OBJS) arc.o
	$(HOSTCXX) -O3 $^ -o $@

clean:
	rm -f arc.h arc.ll borg.mlir state.json *.o direct_sim

# Regression: the direct path must reproduce the firmware-path goldens bit for bit.
GOLD = ../golden
.PHONY: golden
golden: direct_sim
	@for pair in borgvk:vkcube blendcube:blendcube stencilcube:stencilcube; do \
	  sc=$${pair%%:*}; g=$${pair##*:}; \
	  ./direct_sim $(GOLD)/$${sc}_capture.bin 128 128 /tmp/direct_$$sc.rgb && \
	  python3 rgb2ppm.py /tmp/direct_$$sc.rgb 128 /tmp/direct_$$sc.ppm && \
	  python3 $(ROOT)/scripts/compare_ppm.py /tmp/direct_$$sc.ppm $(GOLD)/$${g}_cts_uart_00.ppm --max-diff 0 || exit 1; \
	done
