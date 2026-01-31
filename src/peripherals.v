/*
 * Copyright (c) 2025 Michael Bell
 * SPDX-License-Identifier: Apache-2.0
 */

`default_nettype none

module tinyQV_peripherals #(parameter CLOCK_MHZ=64) (
    input           clk,
    input           rst_n,

    input  [7:0]    ui_in,
    input  [7:0]    ui_in_raw,
    output [7:0]    uo_out,

    output          audio,
    output          audio_select,

    input [10:0]    addr_in,
    input [31:0]    data_in,

    input [1:0]     data_write_n,
    input [1:0]     data_read_n,

    output [31:0]   data_out,
    output          data_ready,

    input           data_read_complete,

    output [15:2]   user_interrupts
);

    // --- Data Bus Logic ---
    reg  [31:0] data_out_r;
    reg         data_out_hold;
    reg         data_ready_r;

    wire        read_req = (data_read_n != 2'b11);
    reg [31:0]  data_from_peri;
    reg         data_ready_from_peri;

    wire [1:0]  data_read_n_peri = data_read_n | {2{data_ready_r}};

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
        if (addr_in[10] == 1'b1 || addr_in[9] == 1'b1) begin
            if (addr_in[10:9] == 2'b11 || addr_in[10:6] == 5'b10111) peri_user[PERI_BORG] = 1'b1;
        end else begin
            peri_user[{1'b0, addr_in[9:6]}] = 1'b1;
        end
    end

    // --- GPIO & Pin Muxing (Flattened for Synthesis) ---
    reg [7:0] gpio_out;
    reg [47:0] func_sel; // 8 pins * 6 bits = 48 bits flattened
    
    always @(posedge clk) begin
        if (!rst_n) begin
            gpio_out <= 8'h0;
            // Pins 0,1 = UART (2), others = GPIO (1)
            func_sel <= {6'd1, 6'd1, 6'd1, 6'd1, 6'd1, 6'd1, 6'd2, 6'd2};
        end else if (peri_user[PERI_GPIO]) begin
            if (addr_in[5:0] == 6'h0 && data_write_n != 2'b11) 
                gpio_out <= data_in[7:0];
            
            // Addressing specific 6-bit chunks of the func_sel vector
            if ({addr_in[5], addr_in[1:0]} == 3'b100 && data_write_n != 2'b11) begin
                case (addr_in[4:2])
                    3'd0: func_sel[5:0]   <= data_in[5:0];
                    3'd1: func_sel[11:6]  <= data_in[5:0];
                    3'd2: func_sel[17:12] <= data_in[5:0];
                    3'd3: func_sel[23:18] <= data_in[5:0];
                    3'd4: func_sel[29:24] <= data_in[5:0];
                    3'd5: func_sel[35:30] <= data_in[5:0];
                    3'd6: func_sel[41:36] <= data_in[5:0];
                    3'd7: func_sel[47:42] <= data_in[5:0];
                endcase
            end
        end
    end

    // Bus Mux
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
            default: data_from_peri = 32'h0;
        endcase
    end

    // Combinatorial Output Mux
    reg [7:0] uo_out_muxed;
    assign uo_out = uo_out_muxed;

    integer k;
    always @(*) begin
        for (k = 0; k < 8; k = k + 1) begin
            // Extract the 6-bit function selection for pin k
            if (func_sel[k*6 +: 6] == 6'd23)      uo_out_muxed[k] = uo_out_borg[k];
            else if (func_sel[k*6 +: 6] == 6'd2)  uo_out_muxed[k] = uo_out_uart[k];
            else                                  uo_out_muxed[k] = gpio_out[k];
        end
    end

    // --- UART ---
    wire [31:0] data_from_uart;
    wire        data_ready_uart;
    wire [7:0]  uo_out_uart;
    tqvp_uart_wrapper #(.CLOCK_MHZ(CLOCK_MHZ)) i_uart (
        .clk(clk), .rst_n(rst_n), .ui_in(ui_in), .uo_out(uo_out_uart),
        .address(addr_in[5:0]), .data_in(data_in),
        .data_write_n(data_write_n | {2{~peri_user[PERI_UART]}}),
        .data_read_n(data_read_n_peri | {2{~peri_user[PERI_UART]}}),
        .data_out(data_from_uart), .data_ready(data_ready_uart),
        .user_interrupt(user_interrupts[3:2])
    );

    // --- BORG ---
    wire [31:0] data_from_borg;
    wire        data_ready_borg;
    wire [7:0]  uo_out_borg;
    Borg i_user_peri39 (
        .clock(clk), .reset(!rst_n), .io_ui_in(ui_in), .io_uo_out(uo_out_borg),
        .io_address(addr_in[5:0]), .io_data_in(data_in),
        .io_data_write_n(data_write_n | {2{~peri_user[PERI_BORG]}}),
        .io_data_read_n(data_read_n_peri | {2{~peri_user[PERI_BORG]}}),
        .io_data_out(data_from_borg), .io_data_ready(data_ready_borg)
    );

    assign user_interrupts[15:4] = 12'h0;
    assign audio = 1'b0;
    assign audio_select = 1'b0;

endmodule
