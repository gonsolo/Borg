all: peripheral_test tt_test
peripheral_test:
	make -C borg_peripheral borg_test tt_test
generate_verilog:
	make -C borg_peripheral generate_verilog
tt_test: generate_verilog
	make -C test -B borg.test
tt_docs:
	./tt/tt_tool.py --create-pdf
