/*
 * Copyright (c) 2024 Caio Alonso da Costa
 * Copyright (c) 2025 Michael Bell
 * SPDX-License-Identifier: Apache-2.0
 */

module spi_reg #(
    parameter int ADDR_W = 3,
    parameter int REG_W = 8
) (
    input  logic clk,
    input  logic rstb,
    input  logic ena,
    input  logic spi_mosi,
    output logic spi_miso,
    input  logic spi_clk,
    input  logic spi_cs_n,
    output logic [ADDR_W-1:0] reg_addr,
    input  logic [REG_W-1:0] reg_data_i,
    output logic [REG_W-1:0] reg_data_o,
    output logic reg_addr_v,
    input  logic reg_data_i_dv,
    output logic reg_data_o_dv,
    output logic reg_rw,
    output logic [1:0] txn_width
);

  // Start of frame - negedge of spi_cs_n
  logic sof;
  // Pulse on start of frame
  falling_edge_detector falling_edge_detector_sof (.rstb(rstb), .clk(clk), .ena(ena), .data(spi_cs_n), .neg_edge(sof));
  // End of frame - posedge of spi_cs_n
  logic eof;
  // Pulse on end of frame
  rising_edge_detector rising_edge_detector_eof (.rstb(rstb), .clk(clk), .ena(ena), .data(spi_cs_n), .pos_edge(eof));

  // Pulses on rising and falling edge of spi_clk
  logic spi_clk_pos;
  logic spi_clk_neg;

  // Pulse on rising edge of spi_clk
  rising_edge_detector rising_edge_detector_spi_clk (.rstb(rstb), .clk(clk), .ena(ena), .data(spi_clk), .pos_edge(spi_clk_pos));
  // Pulse on falling edge of spi_clk
  falling_edge_detector falling_edge_detector_spi_clk (.rstb(rstb), .clk(clk), .ena(ena), .data(spi_clk), .neg_edge(spi_clk_neg));

  // Mask with spi_cs_n
  logic spi_clk_pos_gated;
  logic spi_clk_neg_gated;

  assign spi_clk_pos_gated = spi_clk_pos & ~spi_cs_n;
  assign spi_clk_neg_gated = spi_clk_neg & ~spi_cs_n;

  // Sample data
  logic spi_data_sample;
  // Change data
  logic spi_data_change;

  // Assume mode 00
  assign spi_data_sample = spi_clk_pos_gated;
  assign spi_data_change = spi_clk_neg_gated;

  // FSM states type
  typedef enum logic [2:0] {
    STATE_IDLE, STATE_ADDR, STATE_CMD, STATE_RX_DATA, STATE_TX_LOAD, STATE_TX_DATA
  } fsm_state;

  // FSM states
  fsm_state state, next_state;

  // Next state transition
  always_ff @(negedge(rstb) or posedge(clk)) begin
    if (!rstb) begin
      state <= STATE_IDLE;
    end else begin
      if (ena == 1'b1) begin
        state <= next_state;
      end
    end
  end

  // General counter
  logic [5:0] buffer_counter;

  // Sample addr and data
  logic tx_buffer_load;
  logic sample_addr;
  logic sample_data;

  // Next state logic
  always_comb begin
    // default assignments
    next_state = state;
    tx_buffer_load = 1'b0;
    sample_addr = 1'b0;
    sample_data = 1'b0;

    case (state)
      STATE_IDLE : begin
        if (sof == 1'b1) begin
          next_state = STATE_ADDR;
        end
      end
      STATE_ADDR : begin
        if (buffer_counter == REG_W[5:0]) begin
          sample_addr = 1'b1;
          next_state = STATE_CMD;
        end else if (eof == 1'b1) begin
          next_state = STATE_IDLE;
        end
      end
      STATE_CMD : begin
        if (reg_rw == 1'b0) begin
          next_state = STATE_TX_LOAD;
        end else if (reg_rw == 1'b1) begin
          next_state = STATE_RX_DATA;
        end else if (eof == 1'b1) begin
          next_state = STATE_IDLE;
        end
      end
      STATE_RX_DATA : begin
        if (buffer_counter == REG_W[5:0]) begin
          sample_data = 1'b1;
          next_state = STATE_IDLE;
        end else if (eof == 1'b1) begin
          next_state = STATE_IDLE;
        end
      end
      STATE_TX_LOAD : begin
        tx_buffer_load = 1'b1;
        if (reg_data_i_dv) begin
          next_state = STATE_TX_DATA;
        end
      end
      STATE_TX_DATA : begin
        if (buffer_counter == REG_W[5:0]) begin
          next_state = STATE_IDLE;
        end else if (eof == 1'b1) begin
          next_state = STATE_IDLE;
        end
      end
      default : begin
        next_state = STATE_IDLE;
      end
    endcase
  end

  // Transaction buffer
  logic [REG_W-1:0] txn_buffer;

  // Transaction Buffer
  always_ff @(negedge(rstb) or posedge(clk)) begin
    if (!rstb) begin
      txn_buffer <= '0;
    end else begin
      if (ena == 1'b1) begin
        case (state)
          STATE_IDLE : begin
            txn_buffer <= '0;
          end
          STATE_TX_LOAD : begin
            txn_buffer <= reg_data_i;
          end
          STATE_TX_DATA : begin
            if (spi_data_change == 1'b1 && buffer_counter != 6'd0) begin
              txn_buffer <= {txn_buffer[REG_W-2:0], 1'b0};
            end
          end
          default : begin
            if (spi_data_sample == 1'b1) begin
              txn_buffer <= {txn_buffer[REG_W-2:0], spi_mosi};
            end
          end
        endcase
      end
    end
  end

  // Buffer Counter
  always_ff @(negedge(rstb) or posedge(clk)) begin
    if (!rstb) begin
      buffer_counter <= '0;
    end else begin
      if (ena == 1'b1) begin
        if (buffer_counter == REG_W[5:0]) begin
          buffer_counter <= '0;
        end else if (spi_data_sample == 1'b1) begin
          buffer_counter <= buffer_counter + 1'b1;
        end
      end
    end
  end

  // Addr and Read/Write Command register
  logic [ADDR_W-1:0] addr;

  // Addr and Read/Write Command Registers
  always_ff @(negedge(rstb) or posedge(clk)) begin
    if (!rstb) begin
      addr <= '0;
      reg_rw <= '0;
      txn_width <= 2'b11;
    end else begin
      if (ena == 1'b1) begin
        if (sample_addr == 1'b1) begin
          addr <= txn_buffer[ADDR_W-1:0];
          reg_rw <= txn_buffer[REG_W-1];
          txn_width <= txn_buffer[REG_W-2:REG_W-3];
        end
      end
    end
  end

  // Address output
  assign reg_addr = addr;
  assign reg_addr_v = tx_buffer_load;

  // Data valid strobe
  logic dv;

  // RX buffer can be directly assigned to the data output.  
  // Previously this re-sampled but that cost 32 flops.
  // DV is only indicated at the end of the SPI transaction and txn_buffer will be stable, 
  // so there's no need for the extra 32-flop buffer.
  assign reg_data_o = txn_buffer;
  assign reg_data_o_dv = dv;

  // DataValid (dv) Registers
  always_ff @(negedge(rstb) or posedge(clk)) begin
    if (!rstb) begin
      dv <= '0;
    end else begin
      if (ena == 1'b1) begin
        dv <= '0;
        if (sample_data == 1'b1) begin
          dv <= (1'b1 & reg_rw);
        end
      end
    end
  end

  // MISO output
  assign spi_miso = state == STATE_TX_DATA ? txn_buffer[REG_W-1] : 1'b0;

endmodule
