CC = riscv32-none-elf-gcc
ARCH = rv32ec_zicsr
ABI = ilp32e
AS = riscv32-none-elf-as
AR = riscv32-none-elf-ar
LD = riscv32-none-elf-ld
OBJCOPY = riscv32-none-elf-objcopy

TINYQV_SDK ?= ../sdk

PROJECT_NAME = triangle
PROJECT_SOURCES = borg_triangle.c borg_driver.c borg_fpu.c borg_raster.c borg_spirb.c ../sdk/borg_math.c

all: triangle

triangle:
	$(MAKE) -C compiler all
	bear -- $(MAKE) $(PROJECT_NAME).bin $(PROJECT_NAME).hex

clean:
	rm -f *.o *.elf *.bin *.hex *.asm
	make -C compiler clean

$(TINYQV_SDK)/%.o: $(TINYQV_SDK)/%.s
	$(MAKE) -C $(TINYQV_SDK) $(notdir $@)

%.o: %.c
	$(CC) -O2 -I$(TINYQV_SDK) -march=$(ARCH) -mabi=$(ABI) -fomit-frame-pointer -mno-strict-align -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -c $< -o $@

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
