# Absolute path to RDL-generated headers.
# Exported by the top-level Makefile; derive via git for standalone invocations.
RDL_C_OUT ?= $(shell git rev-parse --show-toplevel)/out/hardware/borg/rdl

# Texture DRAM byte address — derived from borg_layout.h anchors so it
# stays in sync when SEQ_MAX_DRAWS or SEQ_DESC_STRIDE changes.
_desc_base   := $(shell sed -n 's/.*SEQ_DESC_BASE_ADDR[[:space:]]\+\(0x[0-9a-fA-F]\+\).*/\1/p' borg_layout.h)
_max_draws   := $(shell sed -n 's/.*SEQ_MAX_DRAWS[[:space:]]\+\([0-9]\+\).*/\1/p' borg_layout.h)
_desc_stride := $(shell sed -n 's/.*SEQ_DESC_STRIDE[[:space:]]\+\([0-9]\+\).*/\1/p' borg_layout.h)
TEX_SPI_BYTE_ADDR := $(shell printf '%d' $$(( $(_desc_base) + $(_max_draws) * $(_desc_stride) )))

CC = riscv32-none-elf-gcc
# _zicsr enables CSR instructions (rdcycle) — Hutt implements the cycle/mcycle
# read-side counter; the rest of the ISA is still pure RV32I.
ARCH = rv32i_zicsr
ABI  = ilp32
AS = riscv32-none-elf-as
AR = riscv32-none-elf-ar
LD = riscv32-none-elf-ld
OBJCOPY = riscv32-none-elf-objcopy

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

HUTT_SDK ?= ../hutt

# Clock frequency — must match the FPGA PLL output. Override on the command
# line: make triangle CLOCK_MHZ=125 (ULX3S) or leave as 4 (ASIC/TT).
CLOCK_MHZ ?= 4

PROJECT_NAME = triangle
SHARED_SOURCES = borg_driver.c borg_fpu.c borg_raster.c borg_spirb.c borg_math.c
PROJECT_SOURCES = borg_triangle.c $(SHARED_SOURCES)
HEADERS = $(wildcard *.h)

all: python triangle

.PHONY: all triangle vkcube python test-native clean

triangle: python
	@$(MAKE) -C compiler all
	@bear -- $(MAKE) -s $(PROJECT_NAME).bin $(PROJECT_NAME).hex || $(MAKE) -s $(PROJECT_NAME).bin $(PROJECT_NAME).hex

vkcube: python
	@$(MAKE) -C compiler all
	@bear -- $(MAKE) -s PROJECT_NAME=vkcube PROJECT_SOURCES="borg_vkcube.c $(SHARED_SOURCES)" \
		EXTRA_CFLAGS="$(EXTRA_CFLAGS) $(if $(LARGE_TEX),,-DUSE_SMALL_TEXTURE)" vkcube.bin vkcube.hex || \
		$(MAKE) -s PROJECT_NAME=vkcube PROJECT_SOURCES="borg_vkcube.c $(SHARED_SOURCES)" \
		EXTRA_CFLAGS="$(EXTRA_CFLAGS) $(if $(LARGE_TEX),,-DUSE_SMALL_TEXTURE)" vkcube.bin vkcube.hex

python:
	$(MAKE) -C python

test-native: test_raster.c borg_raster.c borg_math.c test_spirb.c borg_spirb.c
	gcc -O2 -g -Wall -Wno-int-to-pointer-cast test_raster.c borg_raster.c borg_math.c -I. -I$(RDL_C_OUT) -D BORG_BASE=0 -o test_raster -lm
	./test_raster
	gcc -O2 -g -Wall test_spirb.c borg_spirb.c -I. -o test_spirb
	./test_spirb

clean:
	rm -f *.o *.elf *.bin *.hex *.asm test_raster test_spirb .cflags_stamp texture_blob.h vkcube_texture_blob.h
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

# Embed the triangle's 32×32 RGB-FP16 texture as a C array (xxd → test_texture_dat[]).
# The firmware Morton-encodes it into SDRAM at boot via borg_upload_texture(),
# since on ULX3S there is no host to preload the GPU texture region.
texture_blob.h: test_texture.dat
	@echo "// Auto-generated: embedded RGB-FP16 source texture (row-major, 6 B/texel)" > $@
	@echo "#pragma once" >> $@
	@echo "#include <stdint.h>" >> $@
	@echo "" >> $@
	@xxd -i test_texture.dat | sed 's/^unsigned char/static const uint8_t/' >> $@

# borg_triangle.c #includes the generated texture blob.
borg_triangle.o: texture_blob.h

# Embed the vkcube's 64×64 RGB-FP16 texture as a C array (xxd → borg_texture_small_dat[]).
vkcube_texture_blob.h: borg_texture_small.dat
	@echo "// Auto-generated: embedded RGB-FP16 vkcube texture (row-major, 6 B/texel)" > $@
	@echo "#pragma once" >> $@
	@echo "#include <stdint.h>" >> $@
	@echo "" >> $@
	@xxd -i borg_texture_small.dat | sed 's/^unsigned char/static const uint8_t/' >> $@

borg_vkcube.o: vkcube_texture_blob.h

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
