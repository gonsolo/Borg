current_design $::env(DESIGN_NAME)
set_units -time ns

set clock_port __VIRTUAL_CLK__
if { [info exists ::env(CLOCK_PORT)] } {
    set port_count [llength $::env(CLOCK_PORT)]

    if { $port_count == "0" } {
        puts "\[WARNING] No CLOCK_PORT found. A dummy clock will be used."
    } elseif { $port_count != "1" } {
        puts "\[WARNING] Multi-clock files are not currently supported by the base SDC file. Only the first clock will be constrained."
    }

    if { $port_count > "0" } {
        set ::clock_port [lindex $::env(CLOCK_PORT) 0]
    }
}

if { $::env(CLOCK_PORT) == $::env(CLOCK_NET) } {
    set port_args [get_ports $clock_port]
} else {
    # This should actually use CLOCK_PIN?
    set port_args [get_pins [lindex $::env(CLOCK_NET) 0]]
}

puts "\[INFO] Using clock $clock_port…"
create_clock {*}$port_args -name $clock_port -period $::env(CLOCK_PERIOD)

set input_delay_value [expr $::env(CLOCK_PERIOD) * $::env(IO_DELAY_CONSTRAINT) / 100]
set output_delay_value [expr $::env(CLOCK_PERIOD) * $::env(IO_DELAY_CONSTRAINT) / 100]
puts "\[INFO] Setting output delay to: $output_delay_value"
puts "\[INFO] Setting input delay to: $input_delay_value"

set_max_fanout $::env(MAX_FANOUT_CONSTRAINT) [current_design]
if { [info exists ::env(MAX_TRANSITION_CONSTRAINT)] } {
    set_max_transition $::env(MAX_TRANSITION_CONSTRAINT) [current_design]
}
# Design-wide, so it also lands on the pad cells' PAD pins, which carry their
# own ~3.7 pF and are rated for 50 pF (gf180mcu_fd_io__bi_24t). Every such pin
# reports a max-cap "violation" (224 on run holdscope). It cannot be scoped
# away: set_max_capacitance takes no library cells, and OpenSTA applies the
# tightest of design/cell/pin limits, so a looser pad override is ignored.
# Kept anyway because it is the resizer's load target for the whole core
# (the std cells' own ratings are mostly far looser, median 0.71 pF).
if { [info exists ::env(MAX_CAPACITANCE_CONSTRAINT)] } {
    set_max_capacitance $::env(MAX_CAPACITANCE_CONSTRAINT) [current_design]
}

set clocks [get_clocks $clock_port]

# Bidirectional pads
set clk_core_inout_ports [get_ports { 
    bidir_PAD[*]
}] 

set_input_delay -min 0 -clock $clocks $clk_core_inout_ports
set_input_delay -max $input_delay_value -clock $clocks $clk_core_inout_ports
set_output_delay $output_delay_value -clock $clocks $clk_core_inout_ports

# Input-only pads
set clk_core_input_ports [get_ports { 
    rst_n_PAD
    input_PAD[*]
}] 

set_input_delay -min 0 -clock $clocks $clk_core_input_ports
set_input_delay -max $input_delay_value -clock $clocks $clk_core_input_ports

# link_narrow (input_PAD[2]) / link_fast (input_PAD[3]): board-level straps,
# not link data. Held static by the board from before reset through the
# whole session (see BorgOnlyTop.scala's pin map and BorgLinkClockGen's doc
# comment on the training protocol) -- they are never launched synchronously
# by anything, so a setup/hold check against clk_PAD is meaningless for them.
# 2026-09-23, run holdfix: still showed a 0.046 ns hold "violation" on
# input_PAD[2] under the same -min 0 assumption as the link data pins. Tried
# registering it like the data pins (BorgLinkSlave, since reverted) -- that
# broke a real test (BorgGpuMemWordTests.fp32_store_load_over_link) because
# BorgLinkClockGen's phase-lock FSM reads these every cycle from reset and a
# registered strap is transiently wrong before its first clock edge, which
# desyncs a state machine that assumes they're correct from cycle 0. The
# textbook-correct treatment for a genuinely static strap is exempting it
# from timing analysis, not a race fix meant for signals that toggle.
set_false_path -from [get_ports { input_PAD[2] input_PAD[3] }]

# rst_n: asynchronous board reset. It reaches only the first flop of
# BorgOnlyTop's two-flop reset synchronizer (rstSync), whose whole purpose is
# to absorb the missing timing relationship -- the textbook false path for a
# synchronizer input. The core reset downstream of rstSync is launched by a
# clocked flop and fully timed. (Before the synchronizer, rst_n drove every
# flop's reset mux directly: the lone hold violator of run linkfix-0923-1829,
# -0.47 ns at max_ss.)
set_false_path -from [get_ports rst_n_PAD]

# Output load
set cap_load [expr $::env(OUTPUT_CAP_LOAD) / 1000.0]
puts "\[INFO] Setting load to: $cap_load"
set_load $cap_load [all_outputs]

puts "\[INFO] Setting clock uncertainty to: $::env(CLOCK_UNCERTAINTY_CONSTRAINT)"
set_clock_uncertainty $::env(CLOCK_UNCERTAINTY_CONSTRAINT) $clocks

puts "\[INFO] Setting clock transition to: $::env(CLOCK_TRANSITION_CONSTRAINT)"
set_clock_transition $::env(CLOCK_TRANSITION_CONSTRAINT) $clocks

puts "\[INFO] Setting timing derate to: $::env(TIME_DERATING_CONSTRAINT)%"
set_timing_derate -early [expr 1-[expr $::env(TIME_DERATING_CONSTRAINT) / 100]]
set_timing_derate -late [expr 1+[expr $::env(TIME_DERATING_CONSTRAINT) / 100]]

if { [info exists ::env(OPENLANE_SDC_IDEAL_CLOCKS)] && $::env(OPENLANE_SDC_IDEAL_CLOCKS) } {
    unset_propagated_clock [all_clocks]
} else {
    set_propagated_clock [all_clocks]
}

