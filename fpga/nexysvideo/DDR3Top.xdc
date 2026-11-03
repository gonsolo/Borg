## 100 MHz board clock
set_property -dict { PACKAGE_PIN R4  IOSTANDARD LVCMOS33 } [get_ports { clk }]
create_clock -add -name sys_clk_pin -period 10.00 -waveform {0 5} [get_ports { clk }]

## CPU RESET button (active low)
set_property -dict { PACKAGE_PIN G4  IOSTANDARD LVCMOS15 } [get_ports { cpu_resetn }]

## LEDs: LD0 calibrated, LD1 finished, LD2 pass, LD3 fail
set_property -dict { PACKAGE_PIN T14 IOSTANDARD LVCMOS25 } [get_ports { led[0] }]
set_property -dict { PACKAGE_PIN T15 IOSTANDARD LVCMOS25 } [get_ports { led[1] }]
set_property -dict { PACKAGE_PIN T16 IOSTANDARD LVCMOS25 } [get_ports { led[2] }]
set_property -dict { PACKAGE_PIN U16 IOSTANDARD LVCMOS25 } [get_ports { led[3] }]

## Borg side (25 MHz) and MIG ui_clk are crossed with ToggleSync handshakes:
## tell Vivado not to time paths between them.
set_clock_groups -asynchronous -group [get_clocks _mmcm_CLKOUT0] -group [get_clocks clk_pll_i]