all:
	# Run tests in peripheral
	make -C borg_peripheral borg_test tt_test
	# Generate all SystemVerilog files from the submodule.
	make -C borg_peripheral generate_verilog
	# Run tests here
	make -C test -B borg.test
