// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// uart_test.v — Minimal UART TX test for ULX3S debug.
// Sends "U\r\n" continuously at 115200 baud from 25 MHz oscillator.
// Purpose: verify ftdi_rxd pin (L4) and tio connectivity before debugging
// the full SoC UART chain.
//
// Build: cd fpga/ulx3s/debug && make uart_test.bit && openFPGALoader -b ulx3s uart_test.bit

module uart_test (
  input  clk_25mhz,
  output ftdi_rxd,       // FPGA → host TX (pin L4)
  input  ftdi_txd,       // host → FPGA RX (unused here)
  output [7:0] led
);

  // 115200 baud from 25 MHz: 25_000_000 / 115200 = 217 cycles/bit
  localparam CLKS_PER_BIT = 217;

  // Message to transmit: "HELLO\r\n" = 0x48 0x45 0x4C 0x4C 0x4F 0x0D 0x0A
  // Packed as bytes, sent LSB-first in standard UART framing.
  localparam MSG_LEN = 7;
  reg [7:0] msg [0:MSG_LEN-1];
  initial begin
    msg[0] = 8'h48; // 'H'
    msg[1] = 8'h45; // 'E'
    msg[2] = 8'h4C; // 'L'
    msg[3] = 8'h4C; // 'L'
    msg[4] = 8'h4F; // 'O'
    msg[5] = 8'h0D; // '\r'
    msg[6] = 8'h0A; // '\n'
  end

  // ── Baud divider counter ──────────────────────────────────────────────────
  reg [7:0] baud_ctr  = 0;
  reg [3:0] bit_idx   = 0;   // 0=start, 1-8=data, 9=stop
  reg [2:0] msg_idx   = 0;   // which byte in msg[]
  reg [7:0] shift_reg = 8'hFF;
  reg       tx        = 1;   // idle high
  reg [23:0] gap_ctr  = 0;   // inter-message gap (~0.5 s)
  reg        in_gap   = 0;

  assign ftdi_rxd = tx;
  assign led      = {7'b0, ~tx}; // D0 blinks on UART activity

  always @(posedge clk_25mhz) begin
    if (in_gap) begin
      gap_ctr <= gap_ctr + 1;
      if (&gap_ctr) begin          // ~0.67 s at 25 MHz
        in_gap  <= 0;
        msg_idx <= 0;
        baud_ctr <= 0;
        bit_idx  <= 0;
        shift_reg <= msg[0];
        tx        <= 0;           // start bit
      end
    end else begin
      if (baud_ctr == CLKS_PER_BIT - 1) begin
        baud_ctr <= 0;
        if (bit_idx == 9) begin
          // Stop bit just finished
          if (msg_idx == MSG_LEN - 1) begin
            // All bytes sent — pause
            in_gap  <= 1;
            gap_ctr <= 0;
            tx      <= 1;
          end else begin
            // Next byte
            msg_idx   <= msg_idx + 1;
            shift_reg <= msg[msg_idx + 1];
            bit_idx   <= 0;
            tx        <= 0;       // start bit of next byte
          end
        end else if (bit_idx == 0) begin
          // Start bit just done — send bit 0 of data
          tx      <= shift_reg[0];
          shift_reg <= {1'b1, shift_reg[7:1]};
          bit_idx <= 1;
        end else if (bit_idx <= 7) begin
          tx        <= shift_reg[0];
          shift_reg <= {1'b1, shift_reg[7:1]};
          bit_idx   <= bit_idx + 1;
        end else begin
          // bit_idx == 8: stop bit
          tx      <= 1;
          bit_idx <= 9;
        end
      end else begin
        baud_ctr <= baud_ctr + 1;
      end
    end
  end

endmodule
