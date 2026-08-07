// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

`default_nettype none

// Instantiates Borg's existing TT top module (tt_um_gonsolo_borg, same RTL
// used unmodified for the ihp-sg13g2/sky130A GDS flows) directly, mapping
// its 24 signal pins (ui_in[7:0] + uo_out[7:0] + uio[7:0]) onto the first 24
// wafer.space bidir_PAD lanes. Bidir pads can act as pure input (oe=0) or
// pure output (oe=1) as needed, so no dedicated input_PAD/analog_PAD use is
// required. Remaining bidir/input/analog pads are tied off safely, unused.
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

    // Not used: leave input pads with pulls disabled and analog untouched.
    assign input_pu = '0;
    assign input_pd = '0;

    // Bidir lane assignment (24 of NUM_BIDIR_PADS used, uses [8] + [8] + [8]): [7:0]=ui_in (input), [15:8]=uo_out (output),
    // [23:16]=uio (true bidir, oe from Borg's own uio_oe).
    wire [7:0] ui_in    = bidir_in[7:0];
    wire [7:0] uo_out;
    wire [7:0] uio_in   = bidir_in[23:16];
    wire [7:0] uio_out;
    wire [7:0] uio_oe;
    wire       ena = 1'b1;

    assign bidir_out[7:0]   = '0;              // ui_in lanes: no drive
    assign bidir_out[15:8]  = uo_out;
    assign bidir_out[23:16] = uio_out;
    assign bidir_out[NUM_BIDIR_PADS-1:24] = '0; // unused lanes

    assign bidir_oe[7:0]    = '0;              // ui_in lanes: input only
    assign bidir_oe[15:8]   = '1;              // uo_out lanes: output only
    assign bidir_oe[23:16]  = uio_oe;
    assign bidir_oe[NUM_BIDIR_PADS-1:24] = '0;  // unused lanes: input, unconnected

    assign bidir_cs = '0;
    assign bidir_sl = '0;
    assign bidir_ie = ~bidir_oe;
    assign bidir_pu = '0;
    assign bidir_pd = '0;

    logic _unused;
    assign _unused = &{bidir_in[NUM_BIDIR_PADS-1:24], &analog};

    tt_um_gonsolo_borg i_borg (
        .ui_in   (ui_in),
        .uo_out  (uo_out),
        .uio_in  (uio_in),
        .uio_out (uio_out),
        .uio_oe  (uio_oe),
        .ena     (ena),
        .clk     (clk),
        .rst_n   (rst_n)
    );

endmodule

`default_nettype wire
