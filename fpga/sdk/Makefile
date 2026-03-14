CC = riscv32-none-elf-gcc
AS = riscv32-none-elf-as
AR = riscv32-none-elf-ar

all: tinyQV.a tinyQV-sim.a start.o

clean:
	rm -f *.o *.a fatfs/*.o sdcard/*.o

%.o: %.c
	$(CC) -O2 -march=rv32ec_zicsr -mabi=ilp32e -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -I$(CURDIR) -c $< -o $@

%.o: %.s
	$(AS) -march=rv32ec_zicsr -mabi=ilp32e $< -o $@

uart_sim.o: uart.c
	$(CC) -DTINYQV_SIM -O2 -march=rv32ec_zicsr -mabi=ilp32e -nostdlib -nostartfiles -ffreestanding -ffunction-sections -fdata-sections -Wall -Werror -lc -I$(CURDIR) -c $< -o $@

uart_buf_sim.o: uart_buf.s
	$(AS) --defsym TINYQV_SIM=1 -march=rv32ec_zicsr -mabi=ilp32e $< -o $@

tinyQV.a: uart.o uart_buf.o mul.o isqrt.o peripheral.o runtime.o spi.o timer.o
	$(AR) rcs $@ $^

tinyQV-sim.a: uart_sim.o uart_buf_sim.o mul.o isqrt.o peripheral.o runtime.o spi.o timer.o
	$(AR) rcs $@ $^

tinyQV-sd.a: sdcard/sdcard.o fatfs/ff.o fatfs/ffsystem.o fatfs/ffunicode.o
	$(AR) rcs $@ $^
