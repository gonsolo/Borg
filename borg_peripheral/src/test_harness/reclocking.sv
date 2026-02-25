/*
 * Copyright (c) 2024 Caio Alonso da Costa
 * SPDX-License-Identifier: Apache-2.0
 */

module reclocking #(parameter int WIDTH = 4) (clk, data_in, data_out);

  input logic clk;
  input logic [WIDTH-1:0] data_in;

  output logic [WIDTH-1:0] data_out;

  logic [WIDTH-1:0] data_sync;

  always_ff @(posedge(clk)) begin
    data_sync <= data_in;
  end

  assign data_out = data_sync;

endmodule
