.PHONY: all clean test borg tinyqv generate_verilog

all: generate_verilog borg tinyqv

generate_verilog:
	$(MAKE) -C .. generate_verilog

borg:
	$(MAKE) -C borg

tinyqv:
	$(MAKE) -C tinyqv

test:
	$(MAKE) -C borg/compiler test

clean:
	$(MAKE) -C borg clean
	$(MAKE) -C tinyqv clean
