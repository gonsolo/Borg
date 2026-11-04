# SPDX-FileCopyrightText: © 2026 Ziad Mubarak
# Pins for nexysvideo_minimal_top (minimal SoC: CPU + UART + HDMI on DDR3).
# DDR3 pins come from the MIG's own pin file.

## Configuration
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property CFGBVS VCCO [current_design]

## 100 MHz board clock
set_property -dict { PACKAGE_PIN R4  IOSTANDARD LVCMOS33 } [get_ports { clk }]
create_clock -add -name sys_clk_pin -period 10.00 -waveform {0 5} [get_ports { clk }]

## CPU RESET button (active low)
set_property -dict { PACKAGE_PIN G4  IOSTANDARD LVCMOS15 } [get_ports { cpu_resetn }]

## Buttons: btn[0] centre, btn[1] up, btn[2] down, btn[3] left, btn[4] right
set_property -dict { PACKAGE_PIN B22 IOSTANDARD LVCMOS12 } [get_ports { btn[0] }]
set_property -dict { PACKAGE_PIN F15 IOSTANDARD LVCMOS12 } [get_ports { btn[1] }]
set_property -dict { PACKAGE_PIN D22 IOSTANDARD LVCMOS12 } [get_ports { btn[2] }]
set_property -dict { PACKAGE_PIN C22 IOSTANDARD LVCMOS12 } [get_ports { btn[3] }]
set_property -dict { PACKAGE_PIN D14 IOSTANDARD LVCMOS12 } [get_ports { btn[4] }]

## LEDs (same layout as ulx3s_minimal_top)
## LD7 clocks stable, LD6 bootDone, LD5-LD2 bootloader step, LD1 memory busy, LD0 UART transmit
set_property -dict { PACKAGE_PIN T14 IOSTANDARD LVCMOS25 } [get_ports { led[0] }]
set_property -dict { PACKAGE_PIN T15 IOSTANDARD LVCMOS25 } [get_ports { led[1] }]
set_property -dict { PACKAGE_PIN T16 IOSTANDARD LVCMOS25 } [get_ports { led[2] }]
set_property -dict { PACKAGE_PIN U16 IOSTANDARD LVCMOS25 } [get_ports { led[3] }]
set_property -dict { PACKAGE_PIN V15 IOSTANDARD LVCMOS25 } [get_ports { led[4] }]
set_property -dict { PACKAGE_PIN W16 IOSTANDARD LVCMOS25 } [get_ports { led[5] }]
set_property -dict { PACKAGE_PIN W15 IOSTANDARD LVCMOS25 } [get_ports { led[6] }]
set_property -dict { PACKAGE_PIN Y13 IOSTANDARD LVCMOS25 } [get_ports { led[7] }]

## UART (names from the PC's view: uart_rx_out is what the FPGA sends)
set_property -dict { PACKAGE_PIN AA19 IOSTANDARD LVCMOS33 } [get_ports { uart_rx_out }]
set_property -dict { PACKAGE_PIN V18  IOSTANDARD LVCMOS33 } [get_ports { uart_tx_in }]

## Flash (SPI, one bit). The flash clock goes through STARTUPE2, so it has no pin here.
set_property -dict { PACKAGE_PIN T19 IOSTANDARD LVCMOS33 } [get_ports { flash_csn }]
set_property -dict { PACKAGE_PIN P22 IOSTANDARD LVCMOS33 } [get_ports { flash_mosi }]
set_property -dict { PACKAGE_PIN R22 IOSTANDARD LVCMOS33 } [get_ports { flash_miso }]
set_property -dict { PACKAGE_PIN P21 IOSTANDARD LVCMOS33 } [get_ports { flash_wp_n }]
set_property -dict { PACKAGE_PIN R21 IOSTANDARD LVCMOS33 } [get_ports { flash_hold_n }]

## HDMI out
set_property -dict { PACKAGE_PIN T1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_clk_p }]
set_property -dict { PACKAGE_PIN U1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_clk_n }]
set_property -dict { PACKAGE_PIN W1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_p[0] }]
set_property -dict { PACKAGE_PIN Y1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_n[0] }]
set_property -dict { PACKAGE_PIN AA1 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_p[1] }]
set_property -dict { PACKAGE_PIN AB1 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_n[1] }]
set_property -dict { PACKAGE_PIN AB3 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_p[2] }]
set_property -dict { PACKAGE_PIN AB2 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_n[2] }]

## SoC clocks (25 and 125 MHz) and the MIG's ui_clk are crossed with ToggleSync handshakes:
## tell Vivado not to time paths between them.
set_clock_groups -asynchronous -group [get_clocks {_mmcm_CLKOUT0 _mmcm_CLKOUT1}] -group [get_clocks clk_pll_i]