all: load

pmod_test.json: pmod_test.v
	yosys -p "read_verilog pmod_test.v; synth_ecp5 -top pmod_test -json pmod_test.json"

pmod_test.config: pmod_test.json pmod_test.lpf
	nextpnr-ecp5 --85k --package CABGA381 \
	  --json pmod_test.json \
	  --lpf pmod_test.lpf \
	  --textcfg pmod_test.config \
	  --freq 25 --timing-allow-fail

pmod_test.bit: pmod_test.config
	ecppack pmod_test.config pmod_test.bit

load: pmod_test.bit
	openFPGALoader -b ulx3s pmod_test.bit
	@echo "PMOD test loaded. LEDs show JEDEC byte 0 (expect 0xEF = 11101111 for Winbond)."
	@echo "Press FIRE1 button to cycle through 3 JEDEC bytes."

clean:
	rm -f pmod_test.json pmod_test.config pmod_test.bit

.PHONY: all load clean
