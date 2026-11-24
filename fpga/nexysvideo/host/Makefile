borg_ftdi: borg_ftdi.c
	$(CC) -O2 -Wall $< $$(pkg-config --cflags --libs libftdi1) -o $@

ftdi_reset: ftdi_reset.c
	$(CC) -O2 -Wall $< $$(pkg-config --cflags --libs libftdi1) -o $@
