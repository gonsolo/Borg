# SPDX-FileCopyrightText: © 2026 Ziad Mubarak
# Write a binary file into the Nexys Video's SPI flash at an offset.
# Usage: vivado -mode batch -source flash.tcl -tclargs <file.bin> <offset, e.g. 0x00400000>

set bin    [lindex $argv 0]
set offset [lindex $argv 1]
set mcs    [file rootname $bin].mcs

# Turn the .bin into a flash image file (.mcs) placed at the offset
write_cfgmem -format mcs -size 32 -interface SPIx1 -loaddata "up $offset $bin" -force $mcs

open_hw_manager
connect_hw_server
open_hw_target
set dev [lindex [get_hw_devices xc7a200t*] 0]
current_hw_device $dev
refresh_hw_device -update_hw_probes false $dev

# The Nexys Video's flash chip (Spansion S25FL256S)
puts "Matching flash parts: [get_cfgmem_parts *s25fl256s*]"
create_hw_cfgmem -hw_device $dev [lindex [get_cfgmem_parts {s25fl256sxxxxxx0-spi-x1_x2_x4}] 0]
set cfg [get_property PROGRAM.HW_CFGMEM $dev]
set_property PROGRAM.ADDRESS_RANGE {use_file} $cfg   ;# only erase/write the sectors the file covers
set_property PROGRAM.FILES [list $mcs] $cfg
set_property PROGRAM.UNUSED_PIN_TERMINATION {pull-none} $cfg
set_property PROGRAM.BLANK_CHECK 0 $cfg
set_property PROGRAM.ERASE 1 $cfg
set_property PROGRAM.CFG_PROGRAM 1 $cfg
set_property PROGRAM.VERIFY 1 $cfg

# Vivado loads a helper bitstream into the FPGA to write the flash
create_hw_bitstream -hw_device $dev [get_property PROGRAM.HW_CFGMEM_BITFILE $dev]
program_hw_devices $dev
refresh_hw_device $dev
program_hw_cfgmem -hw_cfgmem $cfg

close_hw_target
disconnect_hw_server
close_hw_manager