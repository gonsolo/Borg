// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

`default_nettype none

// Phase 0 area/timing probe ONLY -- not the shipping design. Instantiates
// BorgOnlyTop (Borg + BorgLinkSlave, no Hutt) directly onto the wafer.space
// pad interface, one bit per pad, matching the plan's lane map exactly:
// bidir[45:0] and input[3:0] fill the 1x0.5 slot's full pad budget with no
// unused positions, so this is *not* a stripped-down or tied-off stand-in --
// it is the real pin-facing surface of the design being measured, wired to
// genuine top-level primary inputs throughout. That matters for the area
// number to mean anything: a literal Verilog constant on an input lane would
// let Yosys constant-propagate and silently prune "dead" logic downstream,
// understating the area. Every pad here is a true chip_top primary input, so
// no such folding can occur.
//
// This file intentionally does NOT touch the real src/chip_core.sv (which
// backs the currently-signed-off Hutt+Borg SoC on SLOT=1x0p5) or chip_top.sv
// (the shared vendor padring template, reused unmodified -- chip_top.sv only
// cares that a module literally named `chip_core` with this port list exists
// somewhere in the file list, not which file supplies it).
module chip_core #(
    parameter NUM_INPUT_PADS,
    parameter NUM_BIDIR_PADS,
    parameter NUM_ANALOG_PADS
    )(
    `ifdef USE_POWER_PINS
    inout  wire VDD,
    inout  wire VSS,
    `endif

    input  wire clk,
    input  wire rst_n,

    input  wire [NUM_INPUT_PADS-1:0] input_in,
    output wire [NUM_INPUT_PADS-1:0] input_pu,
    output wire [NUM_INPUT_PADS-1:0] input_pd,

    input  wire [NUM_BIDIR_PADS-1:0] bidir_in,
    output wire [NUM_BIDIR_PADS-1:0] bidir_out,
    output wire [NUM_BIDIR_PADS-1:0] bidir_oe,
    output wire [NUM_BIDIR_PADS-1:0] bidir_cs,
    output wire [NUM_BIDIR_PADS-1:0] bidir_sl,
    output wire [NUM_BIDIR_PADS-1:0] bidir_ie,
    output wire [NUM_BIDIR_PADS-1:0] bidir_pu,
    output wire [NUM_BIDIR_PADS-1:0] bidir_pd,

    inout  wire [NUM_ANALOG_PADS-1:0] analog
);

    // This probe only targets SLOT_1X0P5 (46 bidir, 4 input), which is exactly
    // BorgOnlyTop's pin count -- fail loudly rather than silently truncate or
    // leave dangling bits if it is ever pointed at a different slot.
    if (NUM_BIDIR_PADS != 46 || NUM_INPUT_PADS != 4)
        $error("probe_borgonly_chip_core: expected SLOT_1X0P5 (46 bidir / 4 input)");

    assign input_pu = '0;
    assign input_pd = '0;

    // Schmitt on the (input-direction) dn/up_cred lanes for cable noise
    // immunity, slow slew on the (output-direction) up lanes for SSO -- the
    // final pad-configuration policy from the bridge plan's pin-budget
    // section, applied per-lane rather than the blanket '0 the vendor
    // template ships with.
    wire [45:0] laneIsInput;
    assign laneIsInput[17:0]  = '1; // dn_d, dn_v, dn_p
    assign laneIsInput[18]    = '0; // dn_cred (out)
    assign laneIsInput[36:19] = '0; // up_d, up_v, up_p (out)
    assign laneIsInput[37]    = '1; // up_cred (in)
    assign laneIsInput[45:38] = '0; // link_up, link_err, dbg_o (out)

    assign bidir_cs = laneIsInput;
    assign bidir_sl = ~laneIsInput;
    assign bidir_ie = laneIsInput;
    assign bidir_pu = '0;
    assign bidir_pd = laneIsInput; // unplugged cable reads 0 -- see link plan

    BorgOnlyTop i_borg_only (
        .clk       (clk),
        .rst_n     (rst_n),
        .bidirIn   (bidir_in),
        .bidirOut  (bidir_out),
        .bidirOe   (bidir_oe),
        .inputIn   (input_in)
    );

    logic _unused;
    assign _unused = &analog;

endmodule

`default_nettype wire
