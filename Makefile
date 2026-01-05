all:
	# First, generate all SystemVerilog files from the submodule.
	make -C borg_peripheral generate_verilog
	# Then test it.
	make -C test -B borg.test
