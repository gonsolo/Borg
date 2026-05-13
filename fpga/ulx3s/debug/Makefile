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
	@echo -e "$(BOLD)[FLASH]$(RESET) Writing to config flash (~2 min) ..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash $(TOP).bit

# Fast: load into FPGA SRAM (~5s, lost on power cycle) — use for debug iteration
load: $(TOP).bit
	@echo -e "$(BOLD)[LOAD]$(RESET) Loading into SRAM (volatile) ..."
	openFPGALoader -b ulx3s $(TOP).bit
	@echo -e "$(BOLD)[DONE]$(RESET) Running. Expected LEDs:"
	@echo -e "  LED[7] = done  (FSM completed)"
	@echo -e "  LED[6] = ISSI  (manufacturer 0x9D OK)"
	@echo -e "  LED[3,4] on = capacity 0x18 OK"

hdmi: hdmi_flash_id.bit

HDMI_SRC = hdmi_flash_id.v ecp5pll.sv vga.v vga2dvid.v tmds_encoder.v

hdmi_flash_id.json: $(HDMI_SRC)
	@echo -e "$(BOLD)[SYNTH]$(RESET) hdmi_flash_id ..."
	yosys -q -p "read -sv $(HDMI_SRC); synth_ecp5 -top hdmi_flash_id -json hdmi_flash_id.json"

hdmi_flash_id.config: hdmi_flash_id.json
	@echo -e "$(BOLD)[PNR]$(RESET) hdmi_flash_id ..."
	nextpnr-ecp5 --85k --package CABGA381 --json hdmi_flash_id.json --lpf $(LPF) --textcfg hdmi_flash_id.config --timing-allow-fail --quiet

hdmi_flash_id.bit: hdmi_flash_id.config
	@echo -e "$(BOLD)[PACK]$(RESET) hdmi_flash_id ..."
	ecppack hdmi_flash_id.config hdmi_flash_id.bit

load-hdmi: hdmi_flash_id.bit
	openFPGALoader -b ulx3s hdmi_flash_id.bit

bare: bare_spi_test.bit

bare_spi_test.json: bare_spi_test.v
	@echo -e "$(BOLD)[SYNTH]$(RESET) bare_spi_test ..."
	yosys -q -p "read_verilog bare_spi_test.v; synth_ecp5 -top bare_spi_test -json bare_spi_test.json"

bare_spi_test.config: bare_spi_test.json
	@echo -e "$(BOLD)[PNR]$(RESET) bare_spi_test ..."
	nextpnr-ecp5 --85k --package CABGA381 --json bare_spi_test.json --lpf $(LPF) --textcfg bare_spi_test.config --timing-allow-fail --quiet

bare_spi_test.bit: bare_spi_test.config
	@echo -e "$(BOLD)[PACK]$(RESET) bare_spi_test ..."
	ecppack bare_spi_test.config bare_spi_test.bit

load-bare: bare_spi_test.bit
	openFPGALoader -b ulx3s bare_spi_test.bit

uart: uart_test.bit

uart_test.json: uart_test.v
	@echo -e "$(BOLD)[SYNTH]$(RESET) uart_test ..."
	yosys -q -p "read_verilog uart_test.v; synth_ecp5 -top uart_test -json uart_test.json"

uart_test.config: uart_test.json
	@echo -e "$(BOLD)[PNR]$(RESET) uart_test ..."
	nextpnr-ecp5 --85k --package CABGA381 --json uart_test.json --lpf $(LPF) --textcfg uart_test.config --timing-allow-fail --quiet

uart_test.bit: uart_test.config
	@echo -e "$(BOLD)[PACK]$(RESET) uart_test ..."
	ecppack uart_test.config uart_test.bit

load-uart: uart_test.bit
	@echo -e "$(BOLD)[LOAD]$(RESET) Loading uart_test into SRAM ..."
	openFPGALoader -b ulx3s uart_test.bit
	@echo -e "  Connect with: tio /dev/ttyUSB0"
	@echo -e "  Expect:       HELLO repeated at 115200 baud"

.PHONY: all flash load clean hdmi load-hdmi bare load-bare uart load-uart
