## 100 MHz board clock, reset button, LEDs: LD0 DDR3 calibrated, LD1 clocks locked, LD2/LD3 bytes down/up
set_property -dict { PACKAGE_PIN R4  IOSTANDARD LVCMOS33 } [get_ports { clk }]
create_clock -add -name sys_clk_pin -period 10.00 -waveform {0 5} [get_ports { clk }]
set_property -dict { PACKAGE_PIN G4  IOSTANDARD LVCMOS15 } [get_ports { cpu_resetn }]
set_property -dict { PACKAGE_PIN T14 IOSTANDARD LVCMOS25 } [get_ports { led[0] }]
set_property -dict { PACKAGE_PIN T15 IOSTANDARD LVCMOS25 } [get_ports { led[1] }]
set_property -dict { PACKAGE_PIN T16 IOSTANDARD LVCMOS25 } [get_ports { led[2] }]
set_property -dict { PACKAGE_PIN U16 IOSTANDARD LVCMOS25 } [get_ports { led[3] }]

## FT2232H channel A (DPTI), 245 synchronous FIFO, 60 MHz from the chip
set_property -dict { PACKAGE_PIN Y18 IOSTANDARD LVCMOS33 } [get_ports { prog_clko }]
set_property -dict { PACKAGE_PIN U20 IOSTANDARD LVCMOS33 } [get_ports { prog_d_0 }]
set_property -dict { PACKAGE_PIN P14 IOSTANDARD LVCMOS33 } [get_ports { prog_d_1 }]
set_property -dict { PACKAGE_PIN P15 IOSTANDARD LVCMOS33 } [get_ports { prog_d_2 }]
set_property -dict { PACKAGE_PIN U17 IOSTANDARD LVCMOS33 } [get_ports { prog_d_3 }]
set_property -dict { PACKAGE_PIN R17 IOSTANDARD LVCMOS33 } [get_ports { prog_d_4 }]
set_property -dict { PACKAGE_PIN P16 IOSTANDARD LVCMOS33 } [get_ports { prog_d_5 }]
set_property -dict { PACKAGE_PIN R18 IOSTANDARD LVCMOS33 } [get_ports { prog_d_6 }]
set_property -dict { PACKAGE_PIN N14 IOSTANDARD LVCMOS33 } [get_ports { prog_d_7 }]
set_property -dict { PACKAGE_PIN V17 IOSTANDARD LVCMOS33 } [get_ports { prog_oen }]
set_property -dict { PACKAGE_PIN P19 IOSTANDARD LVCMOS33 } [get_ports { prog_rdn }]
set_property -dict { PACKAGE_PIN N17 IOSTANDARD LVCMOS33 } [get_ports { prog_rxen }]
set_property -dict { PACKAGE_PIN P17 IOSTANDARD LVCMOS33 } [get_ports { prog_siwun }]
set_property -dict { PACKAGE_PIN Y19 IOSTANDARD LVCMOS33 } [get_ports { prog_txen }]
set_property -dict { PACKAGE_PIN R19 IOSTANDARD LVCMOS33 } [get_ports { prog_wrn }]

## HDMI out (TMDS), 640x480 from the scan-out
set_property -dict { PACKAGE_PIN T1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_clk_p }]
set_property -dict { PACKAGE_PIN U1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_clk_n }]
set_property -dict { PACKAGE_PIN W1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_p[0] }]
set_property -dict { PACKAGE_PIN Y1  IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_n[0] }]
set_property -dict { PACKAGE_PIN AA1 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_p[1] }]
set_property -dict { PACKAGE_PIN AB1 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_n[1] }]
set_property -dict { PACKAGE_PIN AB3 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_p[2] }]
set_property -dict { PACKAGE_PIN AB2 IOSTANDARD TMDS_33 } [get_ports { hdmi_tx_n[2] }]

## FT2232H data sheet: its outputs change 1-7.15 ns after CLKOUT, its inputs need 8 ns setup, 0 hold.
create_clock -name usb_clk -period 16.667 [get_ports { prog_clko }]
set usb_in  [get_ports { prog_d_* prog_rxen prog_txen }]
set usb_out [get_ports { prog_d_* prog_oen prog_rdn prog_wrn prog_siwun }]
set_input_delay  -clock usb_clk -max 7.15 $usb_in
set_input_delay  -clock usb_clk -min 1.0  $usb_in
set_output_delay -clock usb_clk -max 8.0  $usb_out
set_output_delay -clock usb_clk -min 0.0  $usb_out
set_property IOB TRUE $usb_out

## Borg (25 MHz), the MIG's ui_clk, the FIFO's and the pixels' clocks meet only in handshakes and Gray-coded FIFOs.
set_clock_groups -asynchronous -group [get_clocks -include_generated_clocks usb_clk] -group [get_clocks _mmcm_CLKOUT0] -group [get_clocks clk_pll_i] -group [get_clocks _mmcm_CLKOUT1]
