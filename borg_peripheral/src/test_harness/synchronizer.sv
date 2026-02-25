/*
 * Copyright (c) 2024 Caio Alonso da Costa
 * SPDX-License-Identifier: Apache-2.0
 */

module synchronizer #(parameter int STAGES = 2, parameter int WIDTH = 4) (clk, data_in, data_out);

  input logic clk;
  input logic [WIDTH-1:0] data_in;
  output logic [WIDTH-1:0] data_out;

  logic [WIDTH-1:0] data_sync [STAGES+1];

  assign data_sync[0] = data_in;

  generate
    for (genvar i=0; i<STAGES; i++) begin : gen_reclocking
      reclocking #(.WIDTH(WIDTH)) reclocking_i0 (.clk(clk), .data_in(data_sync[i]), .data_out(data_sync[i+1]));
    end
  endgenerate
  
  assign data_out = data_sync[STAGES];

endmodule
