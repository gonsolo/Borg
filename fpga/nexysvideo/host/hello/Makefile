CROSS ?= riscv64-none-elf-
hello.bin: hello.S
	$(CROSS)gcc -march=rv64ima -mabi=lp64 -nostdlib -Ttext=0 -o hello.elf $<
	$(CROSS)objcopy -O binary hello.elf $@
