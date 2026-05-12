// ECP5 primitive blackboxes for Yosys synthesis.
// These are opaque cells — synth_ecp5 maps them to actual primitives during P&R.

(* blackbox *)
module Usrmclk (
  input USRMCLKI,
  input USRMCLKTS
);
endmodule

// BB — ECP5 bidirectional IO buffer (Lattice cell name).
// One instance per SDRAM DQ bit. T=0: drive I onto pad B. T=1: high-Z, O reads pad.
(* blackbox *)
module BB (
  inout  B,   // pad (connects to SDRAM DQ pin)
  input  T,   // tristate: 0 = drive, 1 = high-Z
  input  I,   // data to drive onto pad
  output O    // data sampled from pad
);
endmodule
