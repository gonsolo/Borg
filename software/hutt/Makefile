# Bare-metal Hutt runtime, selectable by XLEN: 64 (default, ULX3S -- RV64I +
# Zicsr, soft-float lp64d, HW-verified) or 32 (ASIC/TT -- RV32I + Zicsr,
# ilp32). Both toolchains come from flake.nix's pkgsCross riscv{32,64}-embedded.
XLEN ?= 64

ifeq ($(XLEN),32)
TOOLCHAIN ?= riscv32-none-elf
ARCH = rv32i_zicsr
ABI  = ilp32
MUL_SRC = mul32.s
else
TOOLCHAIN ?= riscv64-none-elf
ARCH = rv64ifd_zicsr
ABI  = lp64d
MUL_SRC = mul.s
endif

CC = $(TOOLCHAIN)-gcc
AS = $(TOOLCHAIN)-as
AR = $(TOOLCHAIN)-ar

all: hutt.a start.o

clean:
	rm -f *.o *.a

%.o: %.c
	$(CC) -O2 -march=$(ARCH) -mabi=$(ABI) -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -I$(CURDIR) -c $< -o $@

%.o: %.s
	$(AS) -march=$(ARCH) -mabi=$(ABI) $< -o $@

mul.o: $(MUL_SRC)
	$(AS) -march=$(ARCH) -mabi=$(ABI) $< -o $@

# mul.o: soft __mulsi3/__muldi3 (the Hutt has no M ext in the compiler's -march,
# so libgcc's hardware-MUL versions can't be used). mul.s (RV64/lp64) and
# mul32.s (RV32/ilp32) use different register-width conventions -- see their
# header comments; XLEN above picks the right one.
# mem.o: byte-loop memcpy/memset/memmove overriding newlib's (which are built
# with compressed instructions the no-C Hutt can't decode).
hutt.a: uart.o mul.o mem.o isqrt.o runtime.o
	$(AR) rcs $@ $^
