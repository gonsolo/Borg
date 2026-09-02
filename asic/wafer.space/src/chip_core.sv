// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

`default_nettype none

// Instantiates the wafer.space Borg-only bridge top (BorgOnlyTop -- Borg
// behind BorgLinkSlave, no Hutt, no QSPI; see the plan doc and
// asic/tt/src/BorgOnlyTop.scala's own header), mapping its full 46 bidir +
// 4 input lanes onto the 1x0.5 slot's padring exactly -- this module is only
// valid for SLOT_1X0P5 (NUM_BIDIR_PADS=46, NUM_INPUT_PADS=4; see
// slot_defines.svh), not the other three slots, since BorgOnlyTop's lane map
// fills the whole 1x0.5 budget with no unused positions.
module chip_core #(
    parameter NUM_INPUT_PADS,
    parameter NUM_BIDIR_PADS,
    parameter NUM_ANALOG_PADS
    )(
    `ifdef USE_POWER_PINS
    inout  wire VDD,
    inout  wire VSS,
    `endif

    input  wire clk,       // clock
    input  wire rst_n,     // reset (active low)

    input  wire [NUM_INPUT_PADS-1:0] input_in,   // Input value
    output wire [NUM_INPUT_PADS-1:0] input_pu,   // Pull-up
    output wire [NUM_INPUT_PADS-1:0] input_pd,   // Pull-down

    input  wire [NUM_BIDIR_PADS-1:0] bidir_in,   // Input value
    output wire [NUM_BIDIR_PADS-1:0] bidir_out,  // Output value
    output wire [NUM_BIDIR_PADS-1:0] bidir_oe,   // Output enable
    output wire [NUM_BIDIR_PADS-1:0] bidir_cs,   // Input type (0=CMOS Buffer, 1=Schmitt Trigger)
    output wire [NUM_BIDIR_PADS-1:0] bidir_sl,   // Slew rate (0=fast, 1=slow)
    output wire [NUM_BIDIR_PADS-1:0] bidir_ie,   // Input enable
    output wire [NUM_BIDIR_PADS-1:0] bidir_pu,   // Pull-up
    output wire [NUM_BIDIR_PADS-1:0] bidir_pd,   // Pull-down

    inout  wire [NUM_ANALOG_PADS-1:0] analog  // Analog
);

    // This lane map only makes sense for exactly 46 bidir + 4 input pads
    // (the 1x0.5 slot). A mismatched slot define would silently truncate/
    // leave lanes unconnected below instead of failing loudly at elaboration.
    if (NUM_BIDIR_PADS != 46 || NUM_INPUT_PADS != 4) begin : slot_mismatch
        $error("chip_core: BorgOnlyTop's lane map requires NUM_BIDIR_PADS=46, NUM_INPUT_PADS=4 (the 1x0.5 slot) -- got %0d/%0d. Build with SLOT=1x0p5.", NUM_BIDIR_PADS, NUM_INPUT_PADS);
    end

    // Not used: leave input pads with pulls disabled and analog untouched.
    assign input_pu = '0;
    assign input_pd = '0;

    logic _unused;
    assign _unused = &analog;

    BorgOnlyTop i_borg (
        .clk      (clk),
        .rst_n    (rst_n),
        .bidirIn  (bidir_in),
        .bidirOut (bidir_out),
        .bidirOe  (bidir_oe),
        .inputIn  (input_in)
    );

    // Schmitt trigger + slow slew on the pads BorgOnlyTop drives as outputs
    // (cable noise immunity, and 20 simultaneous outputs against 7 DVSS pads
    // is a real SSO case -- see the plan's Pin budget section), pull-down on
    // the input-only pads so an unplugged/disconnected far side reads all-
    // zero rather than floating -- which fails the link's odd-parity check
    // by construction, so link_up correctly never asserts instead of racing
    // on undefined pad state.
    assign bidir_cs = ~bidir_oe;
    assign bidir_sl = bidir_oe;
    assign bidir_ie = ~bidir_oe;
    assign bidir_pu = '0;
    assign bidir_pd = ~bidir_oe;

endmodule

`default_nettype wire
