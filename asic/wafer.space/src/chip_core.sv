// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

`default_nettype none

// Instantiates the wafer.space Borg-only bridge top (BorgOnlyTop -- Borg
// behind BorgLinkSlave, no Hutt, no QSPI; see
// asic/wafer/src/BorgOnlyTop.scala's own header) on the padring of one of the
// two slots it has a lane map for: SLOT_1X1 (BorgOnlyTop1x1, 40 bidir + 12
// input, the tapeout slot) or SLOT_1X0P5 (BorgOnlyTop, 46 bidir + 4 input).
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

    // BorgOnlyTop has a lane map for exactly two slots: 1x0.5 (46 bidir +
    // 4 input) and 1x1 (40 bidir + 12 input). The emitted Verilog is built
    // for one of them, so the pad counts must match what it was generated
    // with -- a mismatch would silently truncate or leave lanes unconnected
    // below instead of failing loudly at elaboration.
    //
    // NOTE: this only checks the pad *counts*. It cannot check that the
    // BorgOnlyTop.sv in out/ was emitted for the same slot, since both maps
    // present identical port widths for a given count. Emit with the matching
    // Main (BorgOnlyMain / BorgOnly1x1Main) for the SLOT you build.
    if (!((NUM_BIDIR_PADS == 46 && NUM_INPUT_PADS == 4) ||
          (NUM_BIDIR_PADS == 40 && NUM_INPUT_PADS == 12))) begin : slot_mismatch
        $error("chip_core: BorgOnlyTop's lane map supports NUM_BIDIR_PADS/NUM_INPUT_PADS of 46/4 (SLOT=1x0p5) or 40/12 (SLOT=1x1) -- got %0d/%0d.", NUM_BIDIR_PADS, NUM_INPUT_PADS);
    end

    // Not used: leave input pads with pulls disabled and analog untouched.
    assign input_pu = '0;
    assign input_pd = '0;

    logic _unused;
    assign _unused = &analog;

    // Slot-specific module name, so building against the wrong slot's emission
    // fails as "module not found" rather than silently resizing ports (yosys
    // will happily connect a 46-bit net to a 40-bit port with only a warning).
`ifdef SLOT_1X1
    BorgOnlyTop1x1 i_borg (
`else
    BorgOnlyTop i_borg (
`endif
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
