// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// pin_loopback_test.v — Single-wire continuity/pin-map check for the
// wafer.space Borg-only bridge's rung B (see Phase 5 in
// ~/.claude/plans/ok-plan-the-borg-only-twinkling-engelbart.md).
//
// Drives J1 pin0 GP (site B11, this is dn_d[0] in the real link map) from
// btn[0], and reads back J1 pin9 GN (site B1, up_d[0]) onto led[0]. If a
// jumper wire actually connects those two physical holes, pressing btn[0]
// lights led[0]. led[1] mirrors the raw button state directly (no jumper
// involved) so a dark led[0] can be told apart from "button not working" vs
// "jumper/pin-map wrong". led[7] is a static "bitstream is running" tell.
//
// Build + run: cd fpga/ulx3s/debug && make load-pin-loopback
//              press btn[0] (labelled on-board, site R1) and watch led[0]/led[1]

module pin_loopback_test (
  input  clk_25mhz,
  input  btn0,        // site R1
  output test_out,     // J1 pin0 GP, site B11 (dn_d[0])
  input  test_in,       // J1 pin9 GN, site B1  (up_d[0])
  output [7:0] led
);

  assign test_out = btn0;
  assign led[0]   = test_in;
  assign led[1]   = btn0;
  assign led[6:2] = 5'b0;
  assign led[7]   = 1'b1;

endmodule
