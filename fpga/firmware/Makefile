PROJECT_NAME 	?= borg_test
PROJECT_SOURCES ?= borg_test.c

CC = riscv32-none-elf-gcc
ARCH = rv32ec_zicsr
ABI = ilp32e
AS = riscv32-none-elf-as
AR = riscv32-none-elf-ar
LD = riscv32-none-elf-ld
OBJCOPY = riscv32-none-elf-objcopy

TINYQV_SDK ?= ../sdk

TEST_JSON = ../../data/test_cases.json

all: test_data.h $(PROJECT_NAME).bin $(PROJECT_NAME).hex

triangle:
	bear -- $(MAKE) PROJECT_NAME=triangle PROJECT_SOURCES="triangle.c ../sdk/borg_math.c"

test_data.h: $(TEST_JSON) convert_test_data.py
	python convert_test_data.py

clean:
	cd $(dir $(PROJECT_NAME)) && rm -f *.o *.elf *.bin *.hex test_data.h triangle triangle.asm
	make -C compiler clean

$(TINYQV_SDK)/%.o: $(TINYQV_SDK)/%.s
	$(MAKE) -C $(TINYQV_SDK) $(notdir $@)

triangle.o: compiler/vert.borg.h compiler/rasterize.borg.h

compiler/vert.borg.h: FORCE
	$(MAKE) -C compiler vert.borg.h

compiler/rasterize.borg.h: FORCE
	$(MAKE) -C compiler rasterize.borg.h

FORCE:

%.o: %.c test_data.h
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
