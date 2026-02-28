/* Copyright 2023-2024 (c) Michael Bell
   SPDX-License-Identifier: Apache-2.0
 */

module sim_qspi_pmod (
    // External SPI interface
    input      [3:0] qspi_data_in,
    output reg [3:0] qspi_data_out,
    input            qspi_clk,

    input qspi_flash_select,
    input qspi_ram_a_select,
    input qspi_ram_b_select,

    input debug_clk,
    input [24:0] debug_addr,
    output reg [7:0] debug_data
);

    parameter   ROM_BITS       = 15;
    parameter   RAM_BITS       = 13;

    reg [31:0] cmd;
    reg [24:0] addr;
    reg [5:0] start_count;
    reg reading_dummy;
    reg reading;
    reg writing;
    reg error;

    wire any_select = qspi_flash_select && qspi_ram_a_select && qspi_ram_b_select;

    reg [7:0] rom [0:(1 << ROM_BITS)-1];
    reg [7:0] ram_a [0:(1 << RAM_BITS)-1];
    reg [7:0] ram_b [0:(1 << RAM_BITS)-1];

    parameter INIT_FILE = "";
    initial begin
        if (INIT_FILE != "")
            $readmemh(INIT_FILE, rom);
    end

    wire [5:0] next_start_count = start_count + 1;

    always @(posedge qspi_clk or posedge any_select) begin
        if (any_select) begin
            cmd <= 0;
            start_count <= 0;
        end else begin
            start_count <= next_start_count;

            if (writing) begin
                if (!qspi_ram_a_select) 
                    ram_a[addr[RAM_BITS:1]][(4 - 4*addr[0]) +:4] <= qspi_data_in;
                else if (!qspi_ram_b_select)
                    ram_b[addr[RAM_BITS:1]][(4 - 4*addr[0]) +:4] <= qspi_data_in;
            end else if (!reading && !writing && !error) begin
                cmd <= {cmd[27:0], qspi_data_in};
            end
        end
    end

    always @(negedge qspi_clk or posedge any_select) begin
        if (any_select) begin
            reading <= 0;
            reading_dummy <= 0;
            writing <= 0;
            error <= 0;
            addr <= 0;
        end else begin
            if (reading || writing) begin
                addr <= addr + 1;
            end else if (reading_dummy) begin
                if (start_count < 8 && cmd[3:0] != 4'b1010) begin
                    error <= 1;
                    reading_dummy <= 0;
                end
                if (start_count == 12) begin
                    reading <= 1;
                    reading_dummy <= 0;
                end
            end else if (!error && start_count == (qspi_flash_select ? 8 : 6)) begin
                addr[ROM_BITS:1] <= cmd[ROM_BITS-1:0];
                addr[0] <= 0;
                if (!qspi_flash_select || cmd[31:24] == 8'h0B)
                    reading_dummy <= 1;
                else if (cmd[31:24] == 8'h02)
                    writing <= 1;
                else
                    error <= 1;
            end
        end
    end

    always @(posedge debug_clk) begin
        if (debug_addr[24] == 1'b0)
            debug_data <= rom[debug_addr[ROM_BITS-1:0]];
        else if (debug_addr[23] == 1'b0)
            debug_data <= ram_a[debug_addr[RAM_BITS-1:0]];
        else
            debug_data <= ram_b[debug_addr[RAM_BITS-1:0]];
    end

    always @(*) begin
        if (reading) begin
            if (!qspi_flash_select)
                qspi_data_out = rom[addr[ROM_BITS:1]][(4 - 4*addr[0]) +:4];
            else if (!qspi_ram_a_select)
                qspi_data_out = ram_a[addr[RAM_BITS:1]][(4 - 4*addr[0]) +:4];
            else if (!qspi_ram_b_select)
                qspi_data_out = ram_b[addr[RAM_BITS:1]][(4 - 4*addr[0]) +:4];
            else
                qspi_data_out = 0;
        end else begin
            qspi_data_out = 0;
        end
    end
endmodule
