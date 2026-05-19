CC = riscv32-none-elf-gcc
AS = riscv32-none-elf-as
AR = riscv32-none-elf-ar

ARCH = rv32i
ABI  = ilp32

all: tinyQV.a start.o

clean:
	rm -f *.o *.a

%.o: %.c
	$(CC) -O2 -march=$(ARCH) -mabi=$(ABI) -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -I$(CURDIR) -c $< -o $@

%.o: %.s
	$(AS) -march=$(ARCH) -mabi=$(ABI) $< -o $@

tinyQV.a: uart.o mul.o isqrt.o runtime.o
	$(AR) rcs $@ $^
