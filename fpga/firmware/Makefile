# Environment setup (handled by direnv)
PROJECT_NAME 	?= borg_test
PROJECT_SOURCES ?= borg_test.c

RISCV_TOOLCHAIN ?= ../toolchain/tinyQV
CC = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-gcc
AS = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-as
AR = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-ar
LD = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-ld
OBJCOPY = $(RISCV_TOOLCHAIN)/bin/riscv32-unknown-elf-objcopy

TINYQV_SDK ?= ../sdk

TEST_JSON = ../../data/test_cases.json

all: test_data.h $(PROJECT_NAME).bin $(PROJECT_NAME).hex

test_data.h: $(TEST_JSON) convert_test_data.py
	python convert_test_data.py

clean:
	cd $(dir $(PROJECT_NAME)) && rm -f *.o *.elf *.bin *.hex test_data.h

$(RISCV_TOOLCHAIN):
	$(MAKE) -C ../toolchain

$(TINYQV_SDK)/%.o: $(TINYQV_SDK)/%.s $(RISCV_TOOLCHAIN)
	$(MAKE) -C $(TINYQV_SDK) $(notdir $@)

%.o: %.c test_data.h $(RISCV_TOOLCHAIN)
	$(CC) -O2 -I$(TINYQV_SDK) -march=rv32ec_zicsr -mabi=ilp32e -mno-strict-align -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -c $< -o $@

%.o: %.s $(RISCV_TOOLCHAIN)
	$(AS) -march=rv32ec_zicsr_zcb_zicond -mabi=ilp32e -I$(TINYQV_SDK) $< -o $@

.PHONY: $(TINYQV_SDK)/tinyQV.a
$(TINYQV_SDK)/tinyQV.a: $(RISCV_TOOLCHAIN)
	$(MAKE) -C $(TINYQV_SDK)

$(PROJECT_NAME).elf: $(TINYQV_SDK)/start.o $(PROJECT_SOURCES:.c=.o) $(TINYQV_SDK)/tinyQV.a $(RISCV_TOOLCHAIN)
	$(LD) $(TINYQV_SDK)/start.o $(PROJECT_SOURCES:.c=.o) $(TINYQV_SDK)/tinyQV.a $(RISCV_TOOLCHAIN)/riscv32-unknown-elf/lib/libc.a $(RISCV_TOOLCHAIN)/lib/gcc/riscv32-unknown-elf/*/libgcc.a -T $(TINYQV_SDK)/memmap --gc-sections -o $@

$(PROJECT_NAME).bin: $(PROJECT_NAME).elf
	$(OBJCOPY) $< -O binary $@

$(PROJECT_NAME).hex: $(PROJECT_NAME).bin
	od -An -t x1 -w4 -v $< > $@
