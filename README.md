# Borg TinyQV - Floating point addition and multiplication

This is a small project testing floating point addition and multiplication
in Chisel. It is targetted and being taped out with Tinytapeout.

The Makefile does the following:
1. Run tests in the peripheral submodule.
2. Generate all SystemVerilog files from the submodule.
3. Run tests in this small SOC.
	
# Steps to run

1. Clone with ```git clone --recurse-submodules  https://github.com/gonsolo/borg_tinyqv.git```
2. ```make```
