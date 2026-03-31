.PHONY: all clean borg tinyqv generate_verilog

all: generate_verilog borg tinyqv

generate_verilog:
	$(MAKE) -C .. generate_verilog

borg:
	$(MAKE) -C borg

tinyqv:
	$(MAKE) -C tinyqv

clean:
	$(MAKE) -C borg clean
	$(MAKE) -C tinyqv clean
