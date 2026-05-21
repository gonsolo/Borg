all: load

sdram_test.json: sdram_test.v sdram_pnru.v ecp5pll.sv
	yosys -p "read_verilog -sv ecp5pll.sv; read_verilog sdram_pnru.v sdram_test.v; synth_ecp5 -top sdram_test -json sdram_test.json"

sdram_test.config: sdram_test.json sdram_test.lpf
	nextpnr-ecp5 --85k --package CABGA381 \
	  --json sdram_test.json \
	  --lpf sdram_test.lpf \
	  --textcfg sdram_test.config \
	  --freq 125 --timing-allow-fail

sdram_test.bit: sdram_test.config
	ecppack sdram_test.config sdram_test.bit

load: sdram_test.bit
	openFPGALoader -b ulx3s sdram_test.bit

clean:
	rm -f sdram_test.json sdram_test.config sdram_test.bit

.PHONY: all load clean
