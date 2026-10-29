# SPDX-FileCopyrightText: © 2026 Ziad Mubarak
# Load a bitstream onto the Nexys Video over USB-JTAG.
# Usage: vivado -mode batch -source program.tcl -tclargs <file.bit>

set bit [lindex $argv 0]

open_hw_manager
connect_hw_server
open_hw_target
set_property PROGRAM.FILE $bit [current_hw_device]
program_hw_devices [current_hw_device]

close_hw_target          ;# let go of the board so the GUI can use it later
disconnect_hw_server
close_hw_manager