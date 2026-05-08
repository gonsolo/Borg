all: load

sdram_test.json: sdram_test.v
	yosys -p "read_verilog sdram_test.v; synth_ecp5 -top sdram_test -json sdram_test.json"

sdram_test.config: sdram_test.json sdram_test.lpf
	nextpnr-ecp5 --85k --package CABGA381 \
	  --json sdram_test.json \
	  --lpf sdram_test.lpf \
	  --textcfg sdram_test.config \
	  --freq 25 --timing-allow-fail

sdram_test.bit: sdram_test.config
	ecppack sdram_test.config sdram_test.bit

load: sdram_test.bit
	openFPGALoader -b ulx3s sdram_test.bit
	@echo "SDRAM test loaded."
	@echo "  LED[7]   = heartbeat (should always blink)"
	@echo "  LED[6]   = PASS (1 = all 16 read-back checks passed)"
	@echo "  LED[5:3] = error count (0 = good)"
	@echo "  LED[2:0] = FSM state (13 = DONE)"
	@echo "Press FIRE1 (btn1) to re-run the test."

clean:
	rm -f sdram_test.json sdram_test.config sdram_test.bit

.PHONY: all load clean
