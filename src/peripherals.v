/*
 * Copyright (c) 2025 Michael Bell
 * SPDX-License-Identifier: Apache-2.0
 */

`default_nettype none

module tinyQV_peripherals #(
    parameter CLOCK_MHZ = 4
) (
    input clk,
    input rst_n,

    input  [7:0] ui_in,
    input  [7:0] ui_in_raw,
    output [7:0] uo_out,

    output audio,
    output audio_select,

    input [10:0] addr_in,
    input [31:0] data_in,

    input [1:0] data_write_n,
    input [1:0] data_read_n,

    output [31:0] data_out,
    output        data_ready,

    input data_read_complete,

    output [15:2] user_interrupts
);

  // --- Data Bus Logic ---
  reg  [31:0] data_out_r;
  reg         data_out_hold;
  reg         data_ready_r;

  wire        read_req = (data_read_n != 2'b11);
  reg  [31:0] data_from_peri;
  reg         data_ready_from_peri;

  wire [ 1:0] data_read_n_peri = data_read_n | {2{data_ready_r}};

  always @(posedge clk) begin
    if (!rst_n) begin
      data_out_hold <= 1'b0;
      data_ready_r  <= 1'b0;
      data_out_r    <= 32'h0;
    end else begin
      if (data_read_complete) data_out_hold <= 1'b0;
      if (!data_out_hold && data_ready_from_peri && read_req) begin
        data_out_hold <= 1'b1;
        data_out_r    <= data_from_peri;
      end
      data_ready_r <= read_req && data_ready_from_peri;
    end
  end

  assign data_out   = data_out_r;
  assign data_ready = data_ready_r || (data_write_n != 2'b11);

  // --- Address Decoding ---
  localparam PERI_GPIO = 1;
  localparam PERI_UART = 2;
  localparam PERI_BORG = 23;

  reg [23:0] peri_user;
  always @(*) begin
    peri_user = 24'h0;
    // Standard peripherals (GPIO=0x40, UART=0x80)
    if (addr_in[10:9] == 2'b00) begin
      peri_user[{1'b0, addr_in[9:6]}] = 1'b1;
    end
    // Borg decode (assuming mapping to 0x10000 base + offset)
    if (addr_in[10:6] == 5'b10111) begin
      peri_user[PERI_BORG] = 1'b1;
    end
  end

  // --- GPIO Logic ---
  reg [7:0] gpio_out;
  always @(posedge clk) begin
    if (!rst_n) gpio_out <= 8'h0;
    else if (peri_user[PERI_GPIO] && addr_in[5:0] == 6'h0 && data_write_n != 2'b11)
      gpio_out <= data_in[7:0];
  end

  // --- Bus Mux ---
  always @(*) begin
    data_ready_from_peri = 1'b1;
    case (1'b1)
      peri_user[PERI_GPIO]: begin
        data_from_peri = (addr_in[5:0] == 6'h0) ? {24'h0, gpio_out} :
                                 (addr_in[5:0] == 6'h4) ? {24'h0, ui_in}    : 32'h0;
      end
      peri_user[PERI_UART]: begin
        data_from_peri = data_from_uart;
        data_ready_from_peri = data_ready_uart;
      end
      peri_user[PERI_BORG]: begin
        data_from_peri = data_from_borg;
        data_ready_from_peri = data_ready_borg;
      end
      default: begin
        data_from_peri = 32'h0;
        data_ready_from_peri = 1'b1;
      end
    endcase
  end

  // --- Physical Pin Mappings ---
  // uo_out[0] is hard-wired to UART TX for your probe
  assign uo_out[0]   = uo_out_uart[0];
  // Pins 1-7 are standard GPIO
  assign uo_out[7:1] = gpio_out[7:1];

  // --- UART Instance ---
  wire [31:0] data_from_uart;
  wire        data_ready_uart;
  wire [ 7:0] uo_out_uart;

  tqvp_uart_wrapper #(
      .CLOCK_MHZ(CLOCK_MHZ)
  ) i_uart (
      .clk(clk),
      .rst_n(rst_n),
      .ui_in(ui_in),
      .uo_out(uo_out_uart),
      .address(addr_in[5:0]),
      .data_in(data_in),
      .data_write_n(data_write_n | {2{~peri_user[PERI_UART]}}),
      .data_read_n(data_read_n_peri | {2{~peri_user[PERI_UART]}}),
      .data_out(data_from_uart),
      .data_ready(data_ready_uart),
      .user_interrupt(user_interrupts[3:2])
  );

  // --- Borg Instance ---
  wire [31:0] data_from_borg;
  wire        data_ready_borg;
  wire [ 7:0] uo_out_borg;  // Internal wire, not currently mapped to uo_out pins

  Borg i_user_peri23 (
      .clock(clk),  // Chisel name for clk
      .reset(!rst_n),  // Chisel name for active-high reset
      .io_ui_in(ui_in),
      .io_uo_out(uo_out_borg),
      .io_address(addr_in[5:0]),
      .io_data_in(data_in),
      .io_data_write_n(data_write_n | {2{~peri_user[PERI_BORG]}}),
      .io_data_read_n(data_read_n_peri | {2{~peri_user[PERI_BORG]}}),
      .io_data_out(data_from_borg),
      .io_data_ready(data_ready_borg)
  );

  assign user_interrupts[15:4] = 12'h0;
  assign audio = 1'b0;
  assign audio_select = 1'b0;

endmodule
