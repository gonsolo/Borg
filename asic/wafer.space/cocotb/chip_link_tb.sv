// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0
//
// chip_link_tb.sv — testbench wrapper around chip_top for the Borg link
// pad-level tests (plan Phase 5, test 8).
//
// Exists because cocotb cannot drive individual bits of a vector, and
// bidir_PAD is shared: we drive the DN lanes and up_cred while the chip
// concurrently drives up_*, dn_cred, link_up/link_err and dbg_o. Driving
// bidir_PAD as a whole would fight the chip's own outputs. The upstream
// template flags exactly this and recommends a wrapper, so this splits the
// bus into a value vector plus a per-bit output-enable vector, both of which
// cocotb *can* write atomically.
//
// Anything not driven here is left to the chip; `drv_oe` must therefore have
// zeros on every lane BorgOnlyTop owns (see its lane map).

`timescale 1ns / 1ps

// Pad counts. At RTL chip_top.sv has already included these; a gate-level
// netlist carries no macros, so pull them in here (the SLOT_* define comes
// from the runner).
`ifndef NUM_BIDIR_PADS
`include "slot_defines.svh"
`endif

module chip_link_tb;

    localparam NUM_INPUT_PADS = `NUM_INPUT_PADS;
    localparam NUM_BIDIR_PADS = `NUM_BIDIR_PADS;

    // Driven by cocotb. clk/rst are variables here and reach chip_top through
    // continuous assignments below: its ports are `inout wire`, and a variable
    // cannot connect to one directly.
    logic clk;
    logic rst_n;
    logic [NUM_BIDIR_PADS-1:0] drv;      // value for lanes we drive
    logic [NUM_BIDIR_PADS-1:0] drv_oe;   // 1 = testbench drives this lane
    logic [NUM_INPUT_PADS-1:0] input_drv;

    // Resolved buses seen by the chip.
    wire [NUM_BIDIR_PADS-1:0] bidir_PAD;
    wire [NUM_INPUT_PADS-1:0] input_PAD;
    wire clk_PAD   = clk;
    wire rst_n_PAD = rst_n;

    // Per-bit tristate: release the lane (z) unless the testbench owns it, so
    // the chip's own drivers win on its outputs and a mis-set drv_oe shows up
    // as a bus conflict (x) rather than silently masking a wiring error.
    genvar i;
    generate
        for (i = 0; i < NUM_BIDIR_PADS; i++) begin : g_bidir
            assign bidir_PAD[i] = drv_oe[i] ? drv[i] : 1'bz;
        end
        for (i = 0; i < NUM_INPUT_PADS; i++) begin : g_input
            assign input_PAD[i] = input_drv[i];
        end
    endgenerate

`ifdef USE_POWER_PINS
    logic VDD, VSS, DVDD, DVSS;
`endif

    initial begin
        drv       = '0;
        drv_oe    = '0;
        input_drv = '0;
        clk       = 1'b0;
        rst_n     = 1'b0;
    end

    chip_top dut (
`ifdef USE_POWER_PINS
        .VDD   (VDD),
        .VSS   (VSS),
        .DVDD  (DVDD),
        .DVSS  (DVSS),
`endif
        .clk_PAD   (clk_PAD),
        .rst_n_PAD (rst_n_PAD),
        .input_PAD (input_PAD),
        .bidir_PAD (bidir_PAD),
        .analog_PAD ()
    );

    initial begin
        $dumpfile("chip_link_tb.fst");
        $dumpvars(0, chip_link_tb);
    end

endmodule
