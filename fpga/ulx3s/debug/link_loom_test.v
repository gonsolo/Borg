// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// link_loom_test.v — Full 20-wire rung-B loom continuity test for the
// wafer.space Borg-only bridge's BorgExternal mode (see Phase 5 in
// ~/.claude/plans/ok-plan-the-borg-only-twinkling-engelbart.md).
//
// Walks a one-hot pattern across all 20 dn_*/far_link_up outputs (one per
// J1/J2 pin -- see ULX3S.scala's ULX3SPins.linkExternalPins) and reads back
// the paired up_*/link_up_loop input at the SAME physical pin. Once per walk
// cycle (all 20 positions), reports a 20-bit pass mask over UART:
//
//   PASS=FFFFF
//
// means all 20 jumpers verified good. Any 0 bit in the mask marks a bad or
// missing jumper -- see the bit map below to find which pin.
//
// Bit -> signal map (MSB nibble first in the printed hex, bit 19 is the
// MSB of the whole mask):
//   0-10  = dn_d[0..10]/up_d[0..10]     (J1 pins 0-10)
//   11-15 = dn_d[11..15]/up_d[11..15]   (J2 pins 14-18)
//   16    = dn_v/up_v                   (J2 pin 19)
//   17    = dn_p/up_p                   (J2 pin 20)
//   18    = dn_cred/up_cred             (J2 pin 21)
//   19    = far_link_up/link_up_loop    (J2 pin 22)
//
// Build + run: cd fpga/ulx3s/debug && make load-link-loom
//              then non-interactively: stty -F /dev/ttyUSB0 115200 raw -echo
//              && timeout 3 cat /dev/ttyUSB0
//              (tio drops lines in batch/non-interactive capture -- use raw
//              stty+cat instead, see feedback_ulx3s_uart_capture memory)
//
// led[7] = heartbeat (toggles once per walk cycle, ~1s)
// led[1] = sticky "all 20 passed" flag for the most recent cycle
// led[0] = UART TX activity

module link_loom_test (
  input  clk_25mhz,
  output ftdi_rxd,        // FPGA -> host TX, site L4
  input  ftdi_txd,        // host -> FPGA RX, unused
  output [19:0] dn_out,
  input  [19:0] up_in,
  output [7:0] led
);

  localparam CLKS_PER_BIT = 217;           // 115200 baud @ 25 MHz
  localparam STEP_CYCLES  = 21'd1_250_000; // ~50 ms settle per walk step

  // ── Walk generator ───────────────────────────────────────────────────────
  reg [4:0]  walk_idx   = 0;   // 0..19
  reg [20:0] step_ctr   = 0;
  reg [19:0] pass_mask  = 0;
  reg        cycle_done = 0;

  assign dn_out = (20'b1 << walk_idx);

  always @(posedge clk_25mhz) begin
    cycle_done <= 0;
    if (step_ctr == STEP_CYCLES - 1) begin
      step_ctr <= 0;
      pass_mask[walk_idx] <= (up_in == dn_out);
      if (walk_idx == 19) begin
        walk_idx   <= 0;
        cycle_done <= 1;
      end else begin
        walk_idx <= walk_idx + 1;
      end
    end else begin
      step_ctr <= step_ctr + 1;
    end
  end

  // ── Latch report + kick off UART TX once per cycle ─────────────────────
  reg [19:0] report_mask = 0;
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

  // ── UART TX: "PASS=XXXXX\r\n" (12 bytes) ────────────────────────────────
  function [7:0] hex_ascii(input [3:0] nib);
    hex_ascii = (nib < 10) ? (8'h30 + nib) : (8'h41 + nib - 8'd10);
  endfunction

  function [7:0] msg_byte(input [3:0] idx, input [19:0] mask);
    case (idx)
      4'd0:    msg_byte = "P";
      4'd1:    msg_byte = "A";
      4'd2:    msg_byte = "S";
      4'd3:    msg_byte = "S";
      4'd4:    msg_byte = "=";
      4'd5:    msg_byte = hex_ascii(mask[19:16]);
      4'd6:    msg_byte = hex_ascii(mask[15:12]);
      4'd7:    msg_byte = hex_ascii(mask[11:8]);
      4'd8:    msg_byte = hex_ascii(mask[7:4]);
      4'd9:    msg_byte = hex_ascii(mask[3:0]);
      4'd10:   msg_byte = 8'h0D;
      default: msg_byte = 8'h0A;
    endcase
  endfunction

  localparam MSG_LEN = 12;
  reg [7:0] baud_ctr  = 0;
  reg [3:0] bit_idx   = 0;
  reg [3:0] msg_idx   = 0;
  reg [7:0] shift_reg = 8'hFF;
  reg       tx        = 1;
  reg       txing     = 0;

  assign ftdi_rxd = tx;
  assign led = {heartbeat, 5'b0, (report_mask == 20'hFFFFF), txing};

  always @(posedge clk_25mhz) begin
    if (!txing) begin
      if (start_tx) begin
        txing     <= 1;
        msg_idx   <= 0;
        baud_ctr  <= 0;
        bit_idx   <= 0;
        shift_reg <= msg_byte(4'd0, report_mask);
        tx        <= 0; // start bit
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
            shift_reg <= msg_byte(msg_idx[3:0] + 4'd1, report_mask);
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
