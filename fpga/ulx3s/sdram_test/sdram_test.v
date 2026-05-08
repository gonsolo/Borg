// Step 2: PLL + UART + SDRAM init check
`default_nettype none
module sdram_test (
    input  wire        clk_25mhz,
    input  wire [6:0]  btn,
    output reg  [7:0]  led,

    // SDRAM
    output wire        sdram_clk,
    output wire        sdram_cke,
    output wire        sdram_csn,
    output wire        sdram_rasn,
    output wire        sdram_casn,
    output wire        sdram_wen,
    output wire [12:0] sdram_a,
    output wire [1:0]  sdram_ba,
    output wire [1:0]  sdram_dqm,
    inout  wire [15:0] sdram_d,

    output wire        ftdi_rxd
);

// ── PLL: 25→125 MHz ──
wire [3:0] clk_o;
wire pll_lock;
wire clk = clk_o[0];

ecp5pll #(
    .in_hz  ( 25_000_000),
    .out0_hz(125_000_000), .out0_deg(0),
    .out1_hz(125_000_000), .out1_deg(90)
) pll_inst (
    .clk_i(clk_25mhz), .clk_o(clk_o),
    .reset(1'b0), .standby(1'b0),
    .phasesel(2'b0), .phasedir(1'b0), .phasestep(1'b0), .phaseloadreg(1'b0),
    .locked(pll_lock)
);

assign sdram_clk = clk_o[1]; // 90° shifted
assign sdram_cke = 1'b1;

// ── sdram_pnru instance ──
wire       ram_rdy;
// ram_rd, ram_wr, ram_ab, ram_di, ram_do, ram_ack declared in test FSM section
sdram_pnru sdram_inst (
    .sys_clk(clk),
    .sys_rd(ram_rd), .sys_wr(ram_wr),
    .sys_ab(ram_ab), .sys_di(ram_di),
    .sys_do(ram_do),  .sys_rdy(ram_rdy), .sys_ack(ram_ack),
    .sdr_ab(sdram_a), .sdr_db(sdram_d),  .sdr_ba(sdram_ba),
    .sdr_n_CS_WE_RAS_CAS({sdram_csn, sdram_wen, sdram_rasn, sdram_casn}),
    .sdr_dqm(sdram_dqm)
);


// ── UART TX ──
reg [7:0]  tx_data  = 0;
reg        tx_start = 0;
wire       tx_busy;

reg [10:0] baud_cnt = 0;  // needs 11 bits: BAUD_DIV=1085 > 10-bit max 1023
reg  [9:0] sr = 10'h3ff;
reg  [3:0] bit_cnt = 0;
reg        tx_out = 1;
assign ftdi_rxd = tx_out;

localparam BAUD_DIV = 125_000_000 / 115200; // 1085

always @(posedge clk) begin
    if (bit_cnt == 0) begin
        if (tx_start) begin
            sr       <= {1'b1, tx_data, 1'b0};
            bit_cnt  <= 10;
            baud_cnt <= 0;
        end
    end else begin
        if (baud_cnt == BAUD_DIV - 1) begin
            baud_cnt <= 0;
            tx_out   <= sr[0];
            sr       <= {1'b1, sr[9:1]};
            bit_cnt  <= bit_cnt - 1;
        end else
            baud_cnt <= baud_cnt + 1;
    end
end
assign tx_busy = (bit_cnt > 0);

// ── Step 3: write 0xA5C3 to addr 4, read back, print PASS or FAIL ──
localparam TEST_ADDR = 24'd4;
localparam TEST_DATA = 16'hA5C3;
localparam REPEAT_DLY = 250_000_000; // 2s @ 125MHz

reg        ram_rd  = 0, ram_wr = 0;
reg [23:0] ram_ab  = 0;
reg [15:0] ram_di  = 0;
wire[15:0] ram_do;
wire       ram_ack = ~(ram_rd | ram_wr);

// Connect SDRAM controller signals (override the tied-off ones above)
// (sdram_pnru sys_rd/wr/ab/di/do/ack are already wired to these via
//  the instance above — update that instance to use these regs)

reg [15:0] got  = 0;
reg        pass = 0;

// hex nibble → ASCII (plain function, no array index)
function [7:0] h;
    input [3:0] n;
    h = (n < 10) ? (8'h30 + {4'h0,n}) : (8'h41 + {4'h0,n} - 8'd10);
endfunction

// Send char by char; char_idx into current message
// PASS\r\n              = 6 bytes
// FAIL exp=XXXX got=XXXX\r\n = 26 bytes  (longest)
reg [4:0]  char_idx   = 0;
reg [1:0]  send_state = 0; // 0=idle 1=send 2=wait_busy
reg [27:0] wait_ctr   = 0;

reg [7:0] send_char;
always @(*) begin
    if (pass) begin
        case (char_idx)
            5'd0: send_char="P"; 5'd1: send_char="A";
            5'd2: send_char="S"; 5'd3: send_char="S";
            5'd4: send_char=8'd13; 5'd5: send_char=8'd10;
            default: send_char=8'd0;
        endcase
    end else begin
        // FAIL exp=XXXX got=XXXX\r\n
        case (char_idx)
            5'd0:  send_char="F"; 5'd1:  send_char="A";
            5'd2:  send_char="I"; 5'd3:  send_char="L";
            5'd4:  send_char=" "; 5'd5:  send_char="e";
            5'd6:  send_char="x"; 5'd7:  send_char="p";
            5'd8:  send_char="="; 5'd9:  send_char=h(TEST_DATA[15:12]);
            5'd10: send_char=h(TEST_DATA[11:8]); 5'd11: send_char=h(TEST_DATA[7:4]);
            5'd12: send_char=h(TEST_DATA[3:0]);  5'd13: send_char=" ";
            5'd14: send_char="g"; 5'd15: send_char="o";
            5'd16: send_char="t"; 5'd17: send_char="=";
            5'd18: send_char=h(got[15:12]); 5'd19: send_char=h(got[11:8]);
            5'd20: send_char=h(got[7:4]);   5'd21: send_char=h(got[3:0]);
            5'd22: send_char=8'd13; 5'd23: send_char=8'd10;
            default: send_char=8'd0;
        endcase
    end
end

// ── Test + print FSM ──────────────────────────────────────────────────────────
localparam
    S_WAIT_RDY  = 4'd0,  // wait for SDRAM idle after init
    S_WR        = 4'd1,  // assert write
    S_WR_LOW    = 4'd2,  // wait for rdy to go low (controller accepted)
    S_WR_HIGH   = 4'd3,  // wait for rdy to go high (write done)
    S_WR_DONE   = 4'd4,  // deassert wr
    S_RD        = 4'd5,  // assert read
    S_RD_LOW    = 4'd6,  // wait for rdy to go low
    S_RD_HIGH   = 4'd7,  // wait for rdy to go high (read done, data valid)
    S_RD_DONE   = 4'd8,  // deassert rd, latch data
    S_PRINT     = 4'd9,  // send UART chars
    S_WAIT_TX   = 4'd10, // wait for TX to start
    S_PAUSE     = 4'd11; // wait 2s then repeat

reg [3:0] state = S_WAIT_RDY;

always @(posedge clk) begin
    tx_start <= 0;
    if (!pll_lock) begin
        state <= S_WAIT_RDY; ram_rd <= 0; ram_wr <= 0;
        char_idx <= 0; send_state <= 0;
    end else case (state)

    S_WAIT_RDY: if (ram_rdy) begin
        state  <= S_WR;
    end

    S_WR: begin
        ram_wr <= 1; ram_ab <= TEST_ADDR; ram_di <= TEST_DATA;
        state  <= S_WR_LOW;
    end
    S_WR_LOW:  if (!ram_rdy) state <= S_WR_HIGH;
    S_WR_HIGH: if ( ram_rdy) state <= S_WR_DONE;
    S_WR_DONE: begin
        ram_wr <= 0;  // deassert → sys_ack goes high → controller to IDLE
        state  <= S_RD;
    end

    S_RD: begin
        ram_rd <= 1; ram_ab <= TEST_ADDR;
        state  <= S_RD_LOW;
    end
    S_RD_LOW:  if (!ram_rdy) state <= S_RD_HIGH;
    S_RD_HIGH: if ( ram_rdy) state <= S_RD_DONE;
    S_RD_DONE: begin
        got    <= ram_do;
        pass   <= (ram_do == TEST_DATA);
        ram_rd <= 0;
        char_idx <= 0;
        state  <= S_PRINT;
    end

    S_PRINT: begin
        if (send_char == 0) begin
            wait_ctr <= 0;
            state    <= S_PAUSE;
        end else if (!tx_busy) begin
            tx_data  <= send_char;
            tx_start <= 1;
            char_idx <= char_idx + 1;
            state    <= S_WAIT_TX;
        end
    end
    S_WAIT_TX: begin
        tx_start <= 0;
        if (tx_busy) state <= S_PRINT;
    end

    S_PAUSE: begin
        wait_ctr <= wait_ctr + 1;
        if (wait_ctr == REPEAT_DLY - 1) state <= S_WAIT_RDY;
    end

    endcase
end

// Heartbeat on LED
reg [26:0] hb; always @(posedge clk) hb <= hb + 1;
always @(posedge clk) led <= {hb[26], pass, 6'd0};

endmodule
`default_nettype wire
