// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// uart_wire_loop.v — Dead-simple electrical proof that the host->FPGA UART RX
// path works. Wires ftdi_txd (host -> FPGA, site M1) straight back out to
// ftdi_rxd (FPGA -> host, site L4) combinationally: no baud divider, no state
// machine, no clock. Whatever the host writes to /dev/ttyUSB0 must come back
// byte-for-byte at any baud rate.
//
// Motivation: FPGA->host TX was proven this session (link_loom_test's
// "PASS=FFFFF" arrived on the host), but host->FPGA RX had never been proven
// at all -- and a dead RX pin would look exactly like "firmware ignores every
// packet" (board boots fine, host sends fine, nothing ever renders).
//
// led[0] mirrors the RX line live (flickers during traffic); led[1] is a
// sticky "a start bit was seen at least once" latch, so a brief burst can be
// caught by eye after the fact. led[7] = bitstream alive.
//
// Build + run: cd fpga/ulx3s/debug && make load-uart-wire-loop
//   then: stty -F /dev/ttyUSB0 115200 raw -echo
//         printf 'BORG' > /dev/ttyUSB0 & timeout 2 cat /dev/ttyUSB0

module uart_wire_loop (
  input  clk_25mhz,
  input  ftdi_txd,        // host -> FPGA (site M1)
  output ftdi_rxd,        // FPGA -> host (site L4)
  output [7:0] led
);

  // The whole test: a straight wire back to the host.
  assign ftdi_rxd = ftdi_txd;

  // Sticky "RX line was pulled low at least once" (i.e. a real start bit).
  reg seen_low = 1'b0;
  always @(posedge clk_25mhz)
    if (!ftdi_txd)
      seen_low <= 1'b1;

  assign led[0]   = ~ftdi_txd;   // live mirror (idle high -> led off)
  assign led[1]   = seen_low;    // sticky: any traffic ever seen
  assign led[6:2] = 5'b0;
  assign led[7]   = 1'b1;        // alive

endmodule
