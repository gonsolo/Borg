include ../common.mk

.PHONY: all clean
all: minimal-linux-sim

# Borg-free MinimalSoC (RV64) — real OpenSBI/Linux firmware preloaded via the
# dbg_* backdoor, watches the debug console UART (uo_out bit 6) for boot
# output. Isolates whether a silent real-hardware boot is a MinimalSoC-
# specific wiring bug vs. a genuine CPU/timing issue: see
# hardware/soc/src/MinimalSocSimTop.scala.
MINLINUX_DIR = $(ROOT)/out/hardware/soc/verilog_sim
MINLINUX_SRCS = $(wildcard $(MINLINUX_DIR)/*.sv)

.PHONY: minimal-linux-sim
minimal-linux-sim: obj_dir_minlinux/minimal_linux_sim

$(MINLINUX_DIR)/sim_files.txt:
	cd $(ROOT) && CLOCK_MHZ=25 mill hardware.soc.runMain soc.MinimalSocSimMain

obj_dir_minlinux/minimal_linux_sim: main_minimal_linux.cpp $(MINLINUX_DIR)/sim_files.txt $(MINLINUX_SRCS)
	@rm -rf obj_dir_minlinux
	@SRC_FILES=$$(cat $(MINLINUX_DIR)/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator model [MinimalSocSimTop]..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	--trace-fst --trace-structs --savable \
	-MAKEFLAGS "-s" --Mdir obj_dir_minlinux \
	-I$(MINLINUX_DIR) \
	--top-module MinimalSocSimTop \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	main_minimal_linux.cpp \
	../common/uart_decoder.cpp \
	-o minimal_linux_sim

# RV64 SoC with a tiny Borg (see MinimalSocBorgSimMain).
MINLINUXB_DIR = $(ROOT)/out/hardware/soc/verilog_borg_sim
MINLINUXB_SRCS = $(wildcard $(MINLINUXB_DIR)/*.sv)

.PHONY: minimal-linux-borg-sim
minimal-linux-borg-sim: obj_dir_minlinux_borg/minimal_linux_borg_sim

$(MINLINUXB_DIR)/sim_files.txt:
	cd $(ROOT) && CLOCK_MHZ=20 mill hardware.soc.runMain soc.MinimalSocBorgSimMain

obj_dir_minlinux_borg/minimal_linux_borg_sim: main_minimal_linux.cpp $(MINLINUXB_DIR)/sim_files.txt $(MINLINUXB_SRCS)
	@rm -rf obj_dir_minlinux_borg
	@SRC_FILES=$$(cat $(MINLINUXB_DIR)/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator model [MinimalSocSimTop+Borg]..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	--trace-fst --trace-structs --savable \
	-MAKEFLAGS "-s" --Mdir obj_dir_minlinux_borg \
	-I$(MINLINUXB_DIR) \
	--top-module MinimalSocSimTop \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	main_minimal_linux.cpp \
	../common/uart_decoder.cpp \
	-o minimal_linux_borg_sim

# Same test, but through the REAL SdramBackend/SdramController/SdramChipModel
# JEDEC-protocol co-sim instead of the idealized SdramBackendSim — see
# hardware/soc/src/MinimalSocRealSdramSimTop.scala.
MINLINUX_REALSDRAM_DIR = $(ROOT)/out/hardware/soc/verilog_realsdram_sim
MINLINUX_REALSDRAM_SRCS = $(wildcard $(MINLINUX_REALSDRAM_DIR)/*.sv)

.PHONY: minimal-linux-realsdram-sim
minimal-linux-realsdram-sim: obj_dir_minlinux_realsdram/minimal_linux_realsdram_sim

$(MINLINUX_REALSDRAM_DIR)/sim_files.txt:
	cd $(ROOT) && CLOCK_MHZ=25 mill hardware.soc.runMain soc.MinimalSocRealSdramSimMain

obj_dir_minlinux_realsdram/minimal_linux_realsdram_sim: main_minimal_linux_realsdram.cpp $(MINLINUX_REALSDRAM_DIR)/sim_files.txt $(MINLINUX_REALSDRAM_SRCS)
	@rm -rf obj_dir_minlinux_realsdram
	@SRC_FILES=$$(cat $(MINLINUX_REALSDRAM_DIR)/sim_files.txt | awk -F/ '!seen[$$NF]++' | sed 's|^\.\./|\.\./\.\./|' | tr '\n' ' '); \
	echo "Building Verilator model [MinimalSocRealSdramSimTop]..."; \
	verilator -cc --exe -O3 -x-assign fast -x-initial fast -Wall -Wno-UNUSEDSIGNAL --build -j 0 \
	--trace-fst --trace-structs --savable \
	-MAKEFLAGS "-s" --Mdir obj_dir_minlinux_realsdram \
	-I$(MINLINUX_REALSDRAM_DIR) \
	--top-module MinimalSocRealSdramSimTop \
	-CFLAGS "-fPIC" -CFLAGS "-I../../common" \
	-DENABLE_INITIAL_MEM_ \
	$(ROOT)/lint.vlt \
	$$SRC_FILES \
	main_minimal_linux_realsdram.cpp \
	../common/uart_decoder.cpp \
	-o minimal_linux_realsdram_sim

clean:
	rm -rf obj_dir_minlinux obj_dir_minlinux_borg obj_dir_minlinux_realsdram
