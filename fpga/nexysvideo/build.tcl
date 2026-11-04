# SPDX-FileCopyrightText: © 2026 Ziad Mubarak
# Non-project Vivado build for the Nexys Video (no .xpr project needed).
# Usage: vivado -mode batch -source build.tcl -tclargs <top> <verilog_dir> <xdc> <out_dir> [ip_dir]
#   ip_dir (optional): folder with one IP's .xci (and the MIG's .prj), e.g. ip/mig_7series_0

set top         [lindex $argv 0]   ;# top module
set verilog_dir [lindex $argv 1]   ;# folder with the generated .sv files
set xdc         [lindex $argv 2]   ;# pin file
set out_dir     [lindex $argv 3]   ;# where reports and the .bit go
set ip_dir      [lindex $argv 4]   ;# optional IP folder ("" if not given)
set part        xc7a200tsbg484-1   ;# the Nexys Video's FPGA

file mkdir $out_dir
set_part $part                              ;# read_ip needs the part before synthesis

read_verilog -sv [glob $verilog_dir/*.sv]   ;# all generated SystemVerilog
read_xdc $xdc                               ;# pins and the 100 MHz clock

if {$ip_dir ne ""} {
    # Work on a copy so the generated IP files stay out of the repo.
    set ip_copy $out_dir/ip/[file tail $ip_dir]
    file delete -force $ip_copy
    file mkdir $out_dir/ip
    file copy $ip_dir $ip_copy

    set xci [glob $ip_copy/*.xci]
    set_property board_part digilentinc.com:nexys_video:part0:1.2 [current_project]   ;# the MIG was made with Digilent's board settings
    read_ip $xci
    set_property generate_synth_checkpoint false [get_files $xci]   ;# build the IP with the design
    generate_target all [get_files $xci]                            ;# make the IP's files and its pin file
}

synth_design -top $top -part $part          ;# synthesis
opt_design                                  ;# optimisation
place_design                                ;# placement
route_design                                ;# routing

report_timing_summary -file $out_dir/timing.rpt
report_utilization    -file $out_dir/utilization.rpt
write_bitstream -force $out_dir/$top.bit