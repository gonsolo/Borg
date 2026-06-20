# RV64 bare-metal: matches software/borg (RV64I + Zicsr, soft-float lp64).
TOOLCHAIN64 ?= riscv64-unknown-elf
CC = $(TOOLCHAIN64)-gcc
AS = $(TOOLCHAIN64)-as
AR = $(TOOLCHAIN64)-ar

ARCH = rv64ifd_zicsr
ABI  = lp64d

all: hutt.a start.o

clean:
	rm -f *.o *.a

%.o: %.c
	$(CC) -O2 -march=$(ARCH) -mabi=$(ABI) -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -I$(CURDIR) -c $< -o $@

%.o: %.s
	$(AS) -march=$(ARCH) -mabi=$(ABI) $< -o $@

# mul.s provides RV64-correct soft __mulsi3/__muldi3 (the Hutt has no M ext, and
# the rv64imafdc libgcc ships no __muldi3).  It was rewritten for lp64 — the old
# RV32 version corrupted 64-bit multiplies and clobbered s0/s1 on RV64.
hutt.a: uart.o mul.o isqrt.o runtime.o
	$(AR) rcs $@ $^
