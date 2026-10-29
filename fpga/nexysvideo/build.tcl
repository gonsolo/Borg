# SPDX-FileCopyrightText: © 2026 Ziad Mubarak
# Non-project Vivado build for the Nexys Video (no .xpr project needed).
# Usage: vivado -mode batch -source build.tcl -tclargs <top> <verilog_dir> <xdc> <out_dir>

set top         [lindex $argv 0]   ;# top module
set verilog_dir [lindex $argv 1]   ;# folder with the generated .sv files
set xdc         [lindex $argv 2]   ;# pin file
set out_dir     [lindex $argv 3]   ;# where reports and the .bit go
set part        xc7a200tsbg484-1   ;# the Nexys Video's FPGA

file mkdir $out_dir

read_verilog -sv [glob $verilog_dir/*.sv]   ;# all generated SystemVerilog
read_xdc $xdc                               ;# pins and the 100 MHz clock

synth_design -top $top -part $part          ;# synthesis
opt_design                                  ;# optimisation
place_design                                ;# placement
route_design                                ;# routing

report_timing_summary -file $out_dir/timing.rpt
report_utilization    -file $out_dir/utilization.rpt
write_bitstream -force $out_dir/$top.bit