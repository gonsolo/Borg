all:
	make -C borg_peripheral generate_verilog
	make -C test -B borg.test
