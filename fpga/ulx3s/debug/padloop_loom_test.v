// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// padloop_loom_test.v — Continuity check for rung B's 23-wire pad-loop ribbon
// (BorgPadLoop; see ULX3SPins.padLoopPins, which this mirrors one-for-one).
//
// Same idea as link_loom_test.v but for the pad-loop pin map, which is both
// wider (23 pairs, not 20) and differently assigned -- rung B needs a driving
// pad AND a receiving pad per logical wire, since master and slave are both on
// this FPGA. Walks a one-hot across all 23 outputs and checks that the paired
// input at the SAME physical pin reads back exactly that pattern.
//
// Reports once per walk cycle: "PASS=7FFFFF" means all 23 jumpers are good
// (23 bits, so the top nibble only has 3 valid bits -- 0x7FFFFF, not FFFFFF).
// A 0 bit marks a bad or missing jumper; bit -> pin map:
//   bit 0-7   = J1 pins 0-7   dn_d[0..7]   (master -> slave data)
//   bit 8,9   = J1 pins 8,9   dn_v, dn_p
//   bit 10    = J1 pin 10     up_cred
//   bit 11-18 = J2 pins 14-21 up_d[0..7]   (slave -> master data)
//   bit 19,20 = J2 pins 22,23 up_v, up_p
//   bit 21    = J2 pin 24     dn_cred
//   bit 22    = J2 pin 25     link_up
//
// Build + run: cd fpga/ulx3s/debug && make load-padloop-loom
//              stty -F /dev/ttyUSB0 115200 raw -echo && timeout 4 cat /dev/ttyUSB0

module padloop_loom_test (
  input  clk_25mhz,
  output ftdi_rxd,
  input  ftdi_txd,
  output [22:0] pl_out,
  input  [22:0] pl_in,
  output [7:0]  led
);

  localparam CLKS_PER_BIT = 217;           // 115200 baud @ 25 MHz
  localparam STEP_CYCLES  = 21'd1_250_000; // ~50 ms settle per walk step
  localparam NWIRES       = 23;
  localparam ALL_PASS     = 23'h7FFFFF;

  reg [4:0]  walk_idx   = 0;   // 0..22
  reg [20:0] step_ctr   = 0;
  reg [22:0] pass_mask  = 0;
  reg        cycle_done = 0;

  assign pl_out = (23'b1 << walk_idx);

  always @(posedge clk_25mhz) begin
    cycle_done <= 0;
    if (step_ctr == STEP_CYCLES - 1) begin
      step_ctr <= 0;
      pass_mask[walk_idx] <= (pl_in == pl_out);
      if (walk_idx == NWIRES - 1) begin
        walk_idx   <= 0;
        cycle_done <= 1;
      end else begin
        walk_idx <= walk_idx + 1;
      end
    end else begin
      step_ctr <= step_ctr + 1;
    end
  end

  reg [22:0] report_mask = 0;
  reg        start_tx    = 0;
  reg        heartbeat   = 0;

  always @(posedge clk_25mhz) begin
    start_tx <= 0;
    if (cycle_done) begin
      report_mask <= pass_mask;
      start_tx    <= 1;
      heartbeat   <= ~heartbeat;
    end
  end

  // ── UART TX: "PASS=XXXXXX\r\n" (13 bytes) ──────────────────────────────
  function [7:0] hex_ascii(input [3:0] nib);
    hex_ascii = (nib < 10) ? (8'h30 + nib) : (8'h41 + nib - 8'd10);
  endfunction

  function [7:0] msg_byte(input [3:0] idx, input [22:0] mask);
    case (idx)
      4'd0:    msg_byte = "P";
      4'd1:    msg_byte = "A";
      4'd2:    msg_byte = "S";
      4'd3:    msg_byte = "S";
      4'd4:    msg_byte = "=";
      4'd5:    msg_byte = hex_ascii({1'b0, mask[22:20]});
      4'd6:    msg_byte = hex_ascii(mask[19:16]);
      4'd7:    msg_byte = hex_ascii(mask[15:12]);
      4'd8:    msg_byte = hex_ascii(mask[11:8]);
      4'd9:    msg_byte = hex_ascii(mask[7:4]);
      4'd10:   msg_byte = hex_ascii(mask[3:0]);
      4'd11:   msg_byte = 8'h0D;
      default: msg_byte = 8'h0A;
    endcase
  endfunction

  localparam MSG_LEN = 13;
  reg [7:0] baud_ctr  = 0;
  reg [3:0] bit_idx   = 0;
  reg [3:0] msg_idx   = 0;
  reg [7:0] shift_reg = 8'hFF;
  reg       tx        = 1;
  reg       txing     = 0;

  assign ftdi_rxd = tx;
  assign led = {heartbeat, 5'b0, (report_mask == ALL_PASS), txing};

  always @(posedge clk_25mhz) begin
    if (!txing) begin
      if (start_tx) begin
        txing     <= 1;
        msg_idx   <= 0;
        baud_ctr  <= 0;
        bit_idx   <= 0;
        shift_reg <= msg_byte(4'd0, report_mask);
        tx        <= 0;
      end
    end else begin
      if (baud_ctr == CLKS_PER_BIT - 1) begin
        baud_ctr <= 0;
        if (bit_idx == 9) begin
          if (msg_idx == MSG_LEN - 1) begin
            txing <= 0;
            tx    <= 1;
          end else begin
            msg_idx   <= msg_idx + 1;
            shift_reg <= msg_byte(msg_idx + 4'd1, report_mask);
            bit_idx   <= 0;
            tx        <= 0;
          end
        end else if (bit_idx == 0) begin
          tx        <= shift_reg[0];
          shift_reg <= {1'b1, shift_reg[7:1]};
          bit_idx   <= 1;
        end else if (bit_idx <= 7) begin
          tx        <= shift_reg[0];
          shift_reg <= {1'b1, shift_reg[7:1]};
          bit_idx   <= bit_idx + 1;
        end else begin
          tx      <= 1;
          bit_idx <= 9;
        end
      end else begin
        baud_ctr <= baud_ctr + 1;
      end
    end
  end

endmodule
