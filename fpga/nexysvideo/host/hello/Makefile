CROSS ?= riscv64-none-elf-
hello.bin: hello.S
	$(CROSS)gcc -march=rv64ima -mabi=lp64 -nostdlib -Ttext=0x80000000 -o hello.elf $<
	$(CROSS)objcopy -O binary hello.elf $@
