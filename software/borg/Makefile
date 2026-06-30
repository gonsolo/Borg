# Absolute path to RDL-generated headers.
# Exported by the top-level Makefile; derive via git for standalone invocations.
RDL_C_OUT ?= $(shell git rev-parse --show-toplevel)/out/hardware/borg/rdl

# Texture DRAM byte address — derived from borg_layout.h anchors so it
# stays in sync when SEQ_MAX_DRAWS or SEQ_DESC_STRIDE changes.
_desc_base   := $(shell sed -n 's/.*SEQ_DESC_BASE_ADDR[[:space:]]\+\(0x[0-9a-fA-F]\+\).*/\1/p' borg_layout.h)
_max_draws   := $(shell sed -n 's/.*SEQ_MAX_DRAWS[[:space:]]\+\([0-9]\+\).*/\1/p' borg_layout.h)
_desc_stride := $(shell sed -n 's/.*SEQ_DESC_STRIDE[[:space:]]\+\([0-9]\+\).*/\1/p' borg_layout.h)
TEX_SPI_BYTE_ADDR := $(shell printf '%d' $$(( $(_desc_base) + $(_max_draws) * $(_desc_stride) )))

# RV64 bare-metal firmware: the ULX3S Hutt core is RV64I + Zicsr (no M/F/D —
# multiply/divide and float are soft, via libgcc).  Matches the sim tops, which
# now run xlen=64.  ABI lp64 (soft-float).  Toolchain: riscv64-none-elf
# (newlib bare-metal, from pkgsCross.riscv64-embedded in flake.nix).
# Override with TOOLCHAIN64= if your prefix differs.
TOOLCHAIN64 ?= riscv64-none-elf
CC = $(TOOLCHAIN64)-gcc
# _zicsr enables CSR instructions (rdcycle) — Hutt implements the cycle/mcycle
# read-side counter; the rest of the ISA is still pure RV64I.
ARCH = rv64ifd_zicsr
ABI  = lp64d
AS = $(TOOLCHAIN64)-as
AR = $(TOOLCHAIN64)-ar
LD = $(TOOLCHAIN64)-ld
OBJCOPY = $(TOOLCHAIN64)-objcopy

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

HUTT_SDK ?= ../hutt

# Clock frequency — must match the FPGA PLL output. Override on the command
# line: make triangle CLOCK_MHZ=125 (ULX3S) or leave as 4 (ASIC/TT).
CLOCK_MHZ ?= 4

PROJECT_NAME = kernel
SHARED_SOURCES = borg_driver.c borg_fpu.c borg_raster.c borg_spirb.c borg_math.c
PROJECT_SOURCES = borg_kernel.c $(SHARED_SOURCES)
HEADERS = $(wildcard *.h)

all: python kernel

.PHONY: all kernel python test-native clean

kernel: python
	@$(MAKE) -C compiler all
	@bear -- $(MAKE) -s $(PROJECT_NAME).bin $(PROJECT_NAME).hex || $(MAKE) -s $(PROJECT_NAME).bin $(PROJECT_NAME).hex

python:
	$(MAKE) -C python

test-native: test_raster.c borg_raster.c borg_math.c test_spirb.c borg_spirb.c
	gcc -O2 -g -Wall -Wno-int-to-pointer-cast test_raster.c borg_raster.c borg_math.c -I. -I$(RDL_C_OUT) -D BORG_BASE=0 -o test_raster -lm
	./test_raster
	gcc -O2 -g -Wall test_spirb.c borg_spirb.c -I. -o test_spirb
	./test_spirb

clean:
	rm -f *.o *.elf *.bin *.hex *.asm test_raster test_spirb .cflags_stamp
	$(MAKE) -C compiler clean
	$(MAKE) -C python clean

$(HUTT_SDK)/%.o: $(HUTT_SDK)/%.s
	$(MAKE) -C $(HUTT_SDK) $(notdir $@)

# Firmware compile flags, factored into one variable so a change to ANY of them
# — notably EXTRA_CFLAGS / CLOCK_MHZ, which differ per target (sim vs ULX3S) —
# forces a rebuild of every object via the .cflags_stamp dep below.  Without
# this, switching targets silently reuses stale objects compiled with the wrong
# address map (e.g. UART_BASE), which breaks the firmware on hardware.
CFLAGS_FW = -O2 -I$(HUTT_SDK) -I$(RDL_C_OUT) -march=$(ARCH) -mabi=$(ABI) \
            -fomit-frame-pointer -mno-strict-align -nostdlib -nostartfiles \
            -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror \
            -lc -DCLOCK_MHZ=$(CLOCK_MHZ) $(EXTRA_CFLAGS)

# Stamp holding the current flags; rewritten ONLY when they actually change
# (cmp), so objects rebuild exactly when — and only when — the flags differ.
.PHONY: force_cflags_check
.cflags_stamp: force_cflags_check
	@printf '%s' '$(CFLAGS_FW)' | cmp -s - $@ 2>/dev/null || printf '%s' '$(CFLAGS_FW)' > $@


%.o: %.c $(HEADERS) .cflags_stamp
	@$(CC) $(CFLAGS_FW) -c $< -o $@

%.o: %.s
	@$(AS) -march=$(ARCH) -mabi=$(ABI) -I$(HUTT_SDK) $< -o $@

.PHONY: $(HUTT_SDK)/hutt.a
$(HUTT_SDK)/hutt.a:
	$(MAKE) -C $(HUTT_SDK)

$(PROJECT_NAME).elf: $(HUTT_SDK)/start.o $(PROJECT_SOURCES:.c=.o) $(HUTT_SDK)/hutt.a
	@$(CC) -march=$(ARCH) -mabi=$(ABI) -nostartfiles -nostdlib -Wl,--gc-sections -Wl,--no-relax -Wl,--defsym,TEX_SPI_BYTE_ADDR=$(TEX_SPI_BYTE_ADDR) -T $(HUTT_SDK)/memmap $(HUTT_SDK)/start.o $(PROJECT_SOURCES:.c=.o) $(HUTT_SDK)/hutt.a -lc -o $@

$(PROJECT_NAME).bin: $(PROJECT_NAME).elf
	@$(OBJCOPY) $< -O binary $@

$(PROJECT_NAME).hex: $(PROJECT_NAME).bin
	@od -An -t x1 -w4 -v $< > $@
