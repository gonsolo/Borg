// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// dip_read.v — Report the 4-position DIP switch's electrical value over UART.
//
// The wafer.space bridge's BorgExternal mode straps link_fast (and dbg_sel /
// link_narrow) to these switches, and link_fast=1 selects the untrained 1:1
// beat rate while 0 selects the trained divide-by-N -- a large behavioural
// difference. PULLMODE=DOWN tells us an *open* switch reads 0, but not which
// physical slide position is open, so measure it rather than guess.
//
// Prints "SW=abcd\r\n" once per ~0.5 s, where a..d are '0'/'1' for
// sw[0]..sw[3] = dbg_sel[0], dbg_sel[1], link_narrow, link_fast.
//
// Build + run: cd fpga/ulx3s/debug && make load-dip-read
//              stty -F /dev/ttyUSB0 115200 raw -echo && timeout 3 cat /dev/ttyUSB0

module dip_read (
  input  clk_25mhz,
  output ftdi_rxd,
  input  ftdi_txd,
  input  [3:0] sw,
  output [7:0] led
);

  localparam CLKS_PER_BIT = 217;   // 115200 baud @ 25 MHz
  localparam MSG_LEN      = 9;     // "SW=abcd\r\n"

  assign led = {4'b1000, sw};      // live mirror, plus an alive bit

  function [7:0] msg_byte(input [3:0] idx, input [3:0] s);
    case (idx)
      4'd0:    msg_byte = "S";
      4'd1:    msg_byte = "W";
      4'd2:    msg_byte = "=";
      4'd3:    msg_byte = s[0] ? "1" : "0";
      4'd4:    msg_byte = s[1] ? "1" : "0";
      4'd5:    msg_byte = s[2] ? "1" : "0";
      4'd6:    msg_byte = s[3] ? "1" : "0";
      4'd7:    msg_byte = 8'h0D;
      default: msg_byte = 8'h0A;
    endcase
  endfunction

  reg [7:0]  baud_ctr  = 0;
  reg [3:0]  bit_idx   = 0;
  reg [3:0]  msg_idx   = 0;
  reg [7:0]  shift_reg = 8'hFF;
  reg        tx        = 1;
  reg        txing     = 0;
  reg [23:0] gap       = 0;

  assign ftdi_rxd = tx;

  always @(posedge clk_25mhz) begin
    if (!txing) begin
      gap <= gap + 1;
      if (&gap[23:0]) begin
        txing     <= 1;
        msg_idx   <= 0;
        baud_ctr  <= 0;
        bit_idx   <= 0;
        shift_reg <= msg_byte(4'd0, sw);
        tx        <= 0;             // start bit
      end
    end else begin
      if (baud_ctr == CLKS_PER_BIT - 1) begin
        baud_ctr <= 0;
        if (bit_idx == 9) begin
          if (msg_idx == MSG_LEN - 1) begin
            txing <= 0;
            gap   <= 0;
            tx    <= 1;
          end else begin
            msg_idx   <= msg_idx + 1;
            shift_reg <= msg_byte(msg_idx + 4'd1, sw);
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
