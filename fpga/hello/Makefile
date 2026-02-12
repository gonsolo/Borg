PROJECT_NAME ?= gonzo

PROJECT_SOURCES ?= main.c

#RISCV_TOOLCHAIN ?= /opt/tinyQV
#RISCV_TOOLCHAIN ?= ../toolchain/tinyQV
RISCV_TOOLCHAIN ?= ./toolchain/tinyQV

CC = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-gcc
AS = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-as
AR = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-ar
LD = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-ld
OBJCOPY = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-objcopy

TINYQV_SDK ?= tinyQV-sdk

all: $(PROJECT_NAME).bin $(PROJECT_NAME).hex

clean:
	cd $(dir $(PROJECT_NAME)) && rm *.o *.elf *.bin *.hex

%.o: %.c
	$(CC) -O2 -I$(TINYQV_SDK) -march=rv32ec_zcb -mabi=ilp32e -mno-strict-align -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -c $< -o $@

%.o: %.s
	$(AS) -march=rv32ec_zcb -mabi=ilp32e $< -o $@

$(PROJECT_NAME).elf: $(TINYQV_SDK)/start.o $(PROJECT_SOURCES:.c=.o)
	$(LD) $^ $(TINYQV_SDK)/tinyQV.a $(RISCV_TOOLCHAIN)/riscv32-unknown-elf/lib/libc.a $(RISCV_TOOLCHAIN)/lib/gcc/riscv32-unknown-elf/*/libgcc.a -T $(TINYQV_SDK)/memmap --gc-sections -o $@

#$(PROJECT_NAME).elf: $(PROJECT_SOURCES:.c=.o)
#	$(LD) $^ $(TINYQV_SDK)/start.o $(TINYQV_SDK)/tinyQV.a $(RISCV_TOOLCHAIN)/riscv32-unknown-elf/lib/libc.a $(RISCV_TOOLCHAIN)/lib/gcc/riscv32-unknown-elf/*/libgcc.a  -T $(TINYQV_SDK)/memmap --gc-sections -o $@

$(PROJECT_NAME).bin: $(PROJECT_NAME).elf
	$(OBJCOPY) $< -O binary $@

$(PROJECT_NAME).hex: $(PROJECT_NAME).bin
	od -An -t x1 -w4 -v $< > $@
