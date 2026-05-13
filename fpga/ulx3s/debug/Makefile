BOLD  := \033[1m
RESET := \033[0m

TOP  = flash_id_test
LPF  = debug.lpf

all: $(TOP).bit

$(TOP).json: $(TOP).v
	@echo -e "$(BOLD)[SYNTH]$(RESET) $(TOP) ..."
	yosys -q -p "read_verilog $(TOP).v; synth_ecp5 -top $(TOP) -json $(TOP).json; stat -top $(TOP)"

$(TOP).config: $(TOP).json
	@echo -e "$(BOLD)[PNR]$(RESET) $(TOP) ..."
	nextpnr-ecp5 --85k --package CABGA381 --json $(TOP).json --lpf $(LPF) --textcfg $(TOP).config --timing-allow-fail --log $(TOP).log --quiet

$(TOP).bit: $(TOP).config
	@echo -e "$(BOLD)[PACK]$(RESET) $(TOP) ..."
	ecppack $(TOP).config $(TOP).bit
	@echo -e "$(BOLD)[DONE]$(RESET) $(TOP).bit ready."

flash: $(TOP).bit
	@echo -e "$(BOLD)[FLASH]$(RESET) Flashing $(TOP).bit ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash $(TOP).bit
	@echo -e "Expected LEDs after power-cycle:"
	@echo -e "  LED[7] = done     (always on if FSM ran)"
	@echo -e "  LED[6] = ISSI OK  (on if manufacturer=0x9D)"
	@echo -e "  LED[3,4] = cap    (on if capacity=0x18)"

clean:
	rm -f *.json *.config *.bit *.log

.PHONY: all flash clean
