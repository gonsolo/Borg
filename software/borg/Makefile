CC = riscv32-none-elf-gcc
ARCH = rv32e_zicsr
ABI = ilp32e
AS = riscv32-none-elf-as
AR = riscv32-none-elf-ar
LD = riscv32-none-elf-ld
OBJCOPY = riscv32-none-elf-objcopy

# Disable Nixpkgs compiler wrapper hardening flags which pollute bare-metal linking
export HARDENING_DISABLE=all
export NIX_HARDENING_ENABLE=
unexport NIX_HARDENING_ENABLE

TINYQV_SDK ?= ../tinyqv

PROJECT_NAME = triangle
SHARED_SOURCES = borg_driver.c borg_fpu.c borg_raster.c borg_spirb.c borg_math.c
PROJECT_SOURCES = borg_triangle.c $(SHARED_SOURCES)
HEADERS = $(wildcard *.h)

all: triangle

triangle:
	$(MAKE) -C compiler all
	bear -- $(MAKE) $(PROJECT_NAME).bin $(PROJECT_NAME).hex

vkcube:
	$(MAKE) -C compiler all
	bear -- $(MAKE) PROJECT_NAME=vkcube PROJECT_SOURCES="borg_vkcube.c $(SHARED_SOURCES)" vkcube.bin vkcube.hex

test-native: test_raster.c borg_raster.c borg_math.c
	gcc -O2 -g -Wall -Wno-int-to-pointer-cast test_raster.c borg_raster.c borg_math.c -I. -I../../out/hardware/borg/rdl -D BORG_BASE=0 -o test_raster -lm
	./test_raster

clean:
	rm -f *.o *.elf *.bin *.hex *.asm test_raster
	make -C compiler clean

$(TINYQV_SDK)/%.o: $(TINYQV_SDK)/%.s
	$(MAKE) -C $(TINYQV_SDK) $(notdir $@)

%.o: %.c $(HEADERS)
	$(CC) -O2 -I$(TINYQV_SDK) -I../../out/hardware/borg/rdl -march=$(ARCH) -mabi=$(ABI) -fomit-frame-pointer -mno-strict-align -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -c $< -o $@

%.o: %.s
	$(AS) -march=$(ARCH) -mabi=$(ABI) -I$(TINYQV_SDK) $< -o $@

.PHONY: $(TINYQV_SDK)/tinyQV.a
$(TINYQV_SDK)/tinyQV.a:
	$(MAKE) -C $(TINYQV_SDK)

$(PROJECT_NAME).elf: $(TINYQV_SDK)/start.o $(PROJECT_SOURCES:.c=.o) $(TINYQV_SDK)/tinyQV.a
	$(CC) -march=$(ARCH) -mabi=$(ABI) -nostartfiles -nostdlib -Wl,--gc-sections -Wl,--no-relax -T $(TINYQV_SDK)/memmap $(TINYQV_SDK)/start.o $(PROJECT_SOURCES:.c=.o) $(TINYQV_SDK)/tinyQV.a -lc -o $@

$(PROJECT_NAME).bin: $(PROJECT_NAME).elf
	$(OBJCOPY) $< -O binary $@

$(PROJECT_NAME).hex: $(PROJECT_NAME).bin
	od -An -t x1 -w4 -v $< > $@
