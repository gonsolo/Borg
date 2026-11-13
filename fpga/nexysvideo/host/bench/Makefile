CROSS ?= riscv64-none-elf-
bench.bin: bench.S
	$(CROSS)gcc -march=rv64ima -mabi=lp64 -nostdlib -Ttext=0x80000000 -o bench.elf $<
	$(CROSS)objcopy -O binary bench.elf $@
