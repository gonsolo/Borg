# Direct simulator: Borg alone (no CPU, no firmware, no UART), driven by a C++ host.
# Model flow: FIRRTL -> arcilator -> arc.o + arc.h.
include ../common.mk

ARCILATOR = arcilator
FIRTOOL   = firtool
LLC       = llc

export HARDENING_DISABLE=all

.PHONY: all clean
all: direct_sim

$(ROOT)/out/hardware/borg/firrtl_direct/BorgDirectSimTop.fir: $(CHISEL_SRCS)
	cd $(ROOT) && mill hardware.soc.runMain soc.BorgDirectSimMain

borg.mlir: $(ROOT)/out/hardware/borg/firrtl_direct/BorgDirectSimTop.fir
	$(FIRTOOL) $< --disable-layers=Verification --ir-hw > $@

arc.ll state.json &: borg.mlir
	$(ARCILATOR) $< --no-runtime --inline --observe-memories --state-file state.json -o arc.ll

arc.o: arc.ll
	$(LLC) -O3 -relocation-model=pic --filetype=obj $< -o $@

arc.h: state.json
	python3 arcilator-header-cpp.py $< > $@

SRCS = main.cpp
OBJS = $(SRCS:.cpp=.o)
# arc.h defines its layout statics non-inline, so exactly one translation unit may include it.
HDRS = direct_sim.h arc.h $(wildcard $(FIRMWARE_DIR)/*.h) $(RDL_C_OUT)/borg_regs.h

%.o: %.cpp $(HDRS)
	$(HOSTCXX) -std=c++17 -O3 -I. -I$(RDL_C_OUT) -I$(FIRMWARE_DIR) -c $< -o $@

direct_sim: $(OBJS) arc.o
	$(HOSTCXX) -O3 $^ -o $@

# The same harness on a Verilated model, for BORG_TRACE printfs (arcilator drops them):
#   BORG_TRACE=1 mill hardware.soc.runMain soc.BorgDirectSimMain   (fresh mill daemon)
#   make direct_sim_v
VDIR = $(ROOT)/out/hardware/borg/verilog_direct
$(VDIR)/filelist.f: $(ROOT)/out/hardware/borg/firrtl_direct/BorgDirectSimTop.fir
	rm -rf $(VDIR) && mkdir -p $(VDIR)
	$(FIRTOOL) $< -O=release --split-verilog --disable-all-randomization \
	  --lowering-options=disallowLocalVariables,disallowPackedArrays,noAlwaysComb -o $(VDIR)

direct_sim_v: $(VDIR)/filelist.f main_v.cpp main.cpp direct_sim.h
	rm -rf obj_v
	verilator --cc --exe --build -j 0 -O3 -x-assign fast -x-initial fast -Wno-fatal -Wno-lint -Wno-style \
	  --Mdir obj_v -I$(VDIR) -I$(VDIR)/verification -I$(VDIR)/verification/assert --top-module BorgDirectSimTop \
	  -CFLAGS "-std=c++17 -O3 -DDIRECT_VERILATOR -I$(CURDIR) -I$(RDL_C_OUT) -I$(FIRMWARE_DIR)" \
	  -DENABLE_INITIAL_MEM_ \
	  $$(sed 's|^|$(VDIR)/|' $(VDIR)/filelist.f) $(wildcard $(VDIR)/verification/layers-*.sv) \
	  $(CURDIR)/main_v.cpp -o direct_sim_v
	cp obj_v/direct_sim_v .

clean:
	rm -rf arc.h arc.ll borg.mlir state.json *.o direct_sim direct_sim_v obj_v

# Regression: the unmodified vkcube's first frame on borgvk and this simulator, bit for bit.
GOLD = ../golden
.PHONY: golden
golden: direct_sim
	env -u DISPLAY -u WAYLAND_DISPLAY BORGVK_DUMP_PPM=/tmp/direct_vkcube.ppm DIRECT_BIN=$(CURDIR)/direct_sim \
	  $(ROOT)/scripts/vkcube_host.sh --c 1 --width 128 --height 128 > /dev/null
	python3 $(ROOT)/scripts/compare_ppm.py /tmp/direct_vkcube.ppm $(GOLD)/vkcube_00.ppm --max-diff 0
