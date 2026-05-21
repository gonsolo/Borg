# Absolute path to RDL-generated headers.
# Exported by the top-level Makefile; derive via git for standalone invocations.
RDL_C_OUT ?= $(shell git rev-parse --show-toplevel)/out/hardware/borg/rdl

# Texture PSRAM byte address — single source of truth is borg_layout.h.
# Extracted here and passed to the linker so memmap ASSERT stays in sync.
TEX_SPI_BYTE_ADDR := $(shell sed -n 's/.*TEX_PSRAM_BYTE_ADDR_FIXED[[:space:]]\+\(0x[0-9a-fA-F]\+\).*/\1/p' borg_layout.h)

CC = riscv32-none-elf-gcc
ARCH = rv32i
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
		EXTRA_CFLAGS="$(if $(LARGE_TEX),,-DUSE_SMALL_TEXTURE)" vkcube.bin vkcube.hex || \
		$(MAKE) -s PROJECT_NAME=vkcube PROJECT_SOURCES="borg_vkcube.c $(SHARED_SOURCES)" \
		EXTRA_CFLAGS="$(if $(LARGE_TEX),,-DUSE_SMALL_TEXTURE)" vkcube.bin vkcube.hex

python:
	$(MAKE) -C python

test-native: test_raster.c borg_raster.c borg_math.c
	gcc -O2 -g -Wall -Wno-int-to-pointer-cast test_raster.c borg_raster.c borg_math.c -I. -I$(RDL_C_OUT) -D BORG_BASE=0 -o test_raster -lm
	./test_raster

clean:
	rm -f *.o *.elf *.bin *.hex *.asm test_raster .cflags_stamp
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
