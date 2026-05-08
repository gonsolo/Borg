// sdram_test.v — Onboard SDRAM sanity-check for ULX3S
//
// Tests the IS42S16160G-7TL (32 MB, 16-bit wide, 4 banks) at 25 MHz / CL=2.
// Writes a walking-1s pattern to 16 consecutive full-row addresses, then reads
// back and compares.  Result visible on LEDs immediately after each read:
//
//   LED[7]   — heartbeat (toggles every ~0.67 s)
//   LED[6]   — PASS: stays 1 after all reads complete without error
//   LED[5:3] — saturating error count (0–7)
//   LED[2:0] — current FSM state (for debugging)
//
// Press BTN_FIRE1 (btn[1]) to re-run the test from scratch.
//
// Timing at 25 MHz (40 ns/cycle), IS42S16160G-7TL spec:
//   tRC  ≥ 60 ns  →  2 cycles (80 ns) ✓
//   tRCD ≥ 15 ns  →  1 cycle  (40 ns) ✓  (we use 2 for safety)
//   tRP  ≥ 15 ns  →  1 cycle  (40 ns) ✓  (we use 2 for safety)
//   CL   = 2      →  data valid 2 cycles after CAS
//   tREF = 64 ms / 8192 rows → one REFRESH every ~7.8 µs (195 cycles @ 25 MHz)
//   We issue AUTO-REFRESH every 150 cycles to be safe.

`default_nettype none

module sdram_test (
    input  wire        clk_25mhz,
    input  wire [6:0]  btn,          // btn[0]=PWRn, btn[1]=FIRE1
    output reg  [7:0]  led,

    // SDRAM (IS42S16160G-7TL — 16-bit data bus, 4 banks, 13-bit row/col)
    output wire        sdram_clk,
    output wire        sdram_cke,
    output wire        sdram_csn,
    output wire        sdram_rasn,
    output wire        sdram_casn,
    output wire        sdram_wen,
    output wire [12:0] sdram_a,
    output wire [1:0]  sdram_ba,
    output wire [1:0]  sdram_dqm,
    inout  wire [15:0] sdram_d
);

// ---------------------------------------------------------------------------
// Forward the system clock directly to SDRAM.  At 25 MHz we don't need a PLL.
// ---------------------------------------------------------------------------
assign sdram_clk = clk_25mhz;

// ---------------------------------------------------------------------------
// Heartbeat
// ---------------------------------------------------------------------------
reg [24:0] hb_cnt;
always @(posedge clk_25mhz) hb_cnt <= hb_cnt + 1;

// ---------------------------------------------------------------------------
// Button debounce / edge-detect for FIRE1 (active-high)
// ---------------------------------------------------------------------------
reg [2:0] btn1_sr;
always @(posedge clk_25mhz) btn1_sr <= {btn1_sr[1:0], btn[1]};
wire btn1_rise = (btn1_sr[2:1] == 2'b01);

// ---------------------------------------------------------------------------
// SDRAM command encoding
// ---------------------------------------------------------------------------
// {CSn, RASn, CASn, WEn}
localparam CMD_NOP       = 4'b1111;
localparam CMD_ACTIVE    = 4'b0011;
localparam CMD_READ      = 4'b0101;
localparam CMD_WRITE     = 4'b0100;
localparam CMD_PRECHARGE = 4'b0010;
localparam CMD_REFRESH   = 4'b0001;
localparam CMD_MRS       = 4'b0000; // Mode-Register Set

// ---------------------------------------------------------------------------
// SDRAM control registers
// ---------------------------------------------------------------------------
reg        sdram_cke_r  = 1'b0;
reg [3:0]  sdram_cmd_r  = CMD_NOP;
reg [12:0] sdram_a_r    = 13'd0;
reg [1:0]  sdram_ba_r   = 2'd0;
reg [1:0]  sdram_dqm_r  = 2'b11;
reg [15:0] sdram_din_r  = 16'd0;
reg        sdram_oe     = 1'b0;  // drive data bus when writing

assign sdram_cke  = sdram_cke_r;
assign sdram_csn  = sdram_cmd_r[3];
assign sdram_rasn = sdram_cmd_r[2];
assign sdram_casn = sdram_cmd_r[1];
assign sdram_wen  = sdram_cmd_r[0];
assign sdram_a    = sdram_a_r;
assign sdram_ba   = sdram_ba_r;
assign sdram_dqm  = sdram_dqm_r;
assign sdram_d    = sdram_oe ? sdram_din_r : 16'hzzzz;

// ---------------------------------------------------------------------------
// FSM states
// ---------------------------------------------------------------------------
localparam [3:0]
    S_RESET      = 4'd0,   // power-up wait (200 µs)
    S_PREALL     = 4'd1,   // PRECHARGE ALL
    S_WAIT_PRE   = 4'd2,   // wait tRP
    S_REFRESH1   = 4'd3,   // 1st AUTO-REFRESH
    S_WAIT_REF1  = 4'd4,
    S_REFRESH2   = 4'd5,   // 2nd AUTO-REFRESH
    S_WAIT_REF2  = 4'd6,
    S_MRS        = 4'd7,   // MODE REGISTER SET (CL=2, BL=1)
    S_WAIT_MRS   = 4'd8,
    S_WRITE      = 4'd9,   // write loop
    S_WAIT_WRITE = 4'd10,
    S_READ       = 4'd11,  // read loop
    S_WAIT_READ  = 4'd12,
    S_DONE       = 4'd13;  // pass/fail display

reg [3:0]  state     = S_RESET;
reg [17:0] timer     = 0;     // general countdown timer
reg [3:0]  loop_idx  = 0;     // 0..15 test addresses
reg [2:0]  err_cnt   = 0;
reg        pass      = 0;

// Refresh counter — issue AUTO-REFRESH every 150 cycles when idle or in DONE
reg [7:0]  ref_timer = 0;
reg        do_ref    = 0;

// ---------------------------------------------------------------------------
// Test pattern: walking 1s (16 patterns for 16 writes)
// ---------------------------------------------------------------------------
function [15:0] pattern;
    input [3:0] idx;
    pattern = (16'd1 << idx);
endfunction

// ---------------------------------------------------------------------------
// Captured read-data (registered on rising edge, sampled 2 cycles after CAS)
// ---------------------------------------------------------------------------
reg [15:0] read_data_cap;
reg        capture_en = 1'b0;
always @(posedge clk_25mhz)
    if (capture_en) read_data_cap <= sdram_d;

// ---------------------------------------------------------------------------
// Main FSM
// ---------------------------------------------------------------------------
// Power-up: need 200 µs = 5000 cycles @ 25 MHz.  Use 18-bit timer.
localparam POWERUP_CYCLES = 18'd5100;

always @(posedge clk_25mhz) begin
    // defaults
    sdram_cmd_r <= CMD_NOP;
    sdram_oe    <= 1'b0;
    capture_en  <= 1'b0;

    // Refresh housekeeping (only in DONE state to avoid complicating the init
    // FSM; during init we issue explicit refreshes)
    if (state == S_DONE) begin
        ref_timer <= ref_timer + 1;
        if (ref_timer == 8'd150) begin
            ref_timer <= 0;
            do_ref    <= 1;
        end
    end

    // Re-run on button press
    if (btn1_rise) begin
        state    <= S_RESET;
        timer    <= 0;
        loop_idx <= 0;
        err_cnt  <= 0;
        pass     <= 0;
        do_ref   <= 0;
        ref_timer<= 0;
    end else begin

    case (state)

    // ---- Power-up wait (200 µs, CKE low then high) -----------------------
    S_RESET: begin
        sdram_cke_r <= 1'b0;
        timer <= timer + 1;
        if (timer == POWERUP_CYCLES - 2) sdram_cke_r <= 1'b1;
        if (timer == POWERUP_CYCLES) begin
            timer <= 0;
            state <= S_PREALL;
        end
    end

    // ---- PRECHARGE ALL ---------------------------------------------------
    S_PREALL: begin
        sdram_cmd_r <= CMD_PRECHARGE;
        sdram_a_r   <= 13'b0010000000000; // A10=1 → all banks
        sdram_ba_r  <= 2'd0;
        state       <= S_WAIT_PRE;
        timer       <= 4;  // tRP = 2 cycles; wait 4 to be safe
    end
    S_WAIT_PRE: begin
        timer <= timer - 1;
        if (timer == 1) state <= S_REFRESH1;
    end

    // ---- AUTO-REFRESH x2 -------------------------------------------------
    S_REFRESH1: begin
        sdram_cmd_r <= CMD_REFRESH;
        state       <= S_WAIT_REF1;
        timer       <= 8;  // tRC min 2 cyc; use 8
    end
    S_WAIT_REF1: begin
        timer <= timer - 1;
        if (timer == 1) state <= S_REFRESH2;
    end
    S_REFRESH2: begin
        sdram_cmd_r <= CMD_REFRESH;
        state       <= S_WAIT_REF2;
        timer       <= 8;
    end
    S_WAIT_REF2: begin
        timer <= timer - 1;
        if (timer == 1) state <= S_MRS;
    end

    // ---- MODE REGISTER SET: CL=2, BL=1 (single), Sequential -------------
    // MR[6:4]=010(CL=2), MR[3]=0(sequential), MR[2:0]=000(BL=1)
    S_MRS: begin
        sdram_cmd_r <= CMD_MRS;
        sdram_ba_r  <= 2'd0;
        sdram_a_r   <= 13'b0000000100000; // CL=2, BL=1
        state       <= S_WAIT_MRS;
        timer       <= 4;
    end
    S_WAIT_MRS: begin
        timer <= timer - 1;
        if (timer == 1) begin
            loop_idx <= 0;
            state    <= S_WRITE;
        end
    end

    // ---- WRITE LOOP ------------------------------------------------------
    // Each test: bank=0, row=0, col=loop_idx
    S_WRITE: begin
        // Cycle 1: ACTIVE
        sdram_cmd_r <= CMD_ACTIVE;
        sdram_ba_r  <= 2'd0;
        sdram_a_r   <= 13'd0;     // row 0
        state       <= S_WAIT_WRITE;
        timer       <= 5;         // sub-state counter
    end
    S_WAIT_WRITE: begin
        timer <= timer - 1;
        case (timer)
            5: ; // NOP (tRCD)
            4: begin
                // WRITE with auto-precharge (A10=1)
                sdram_cmd_r <= CMD_WRITE;
                sdram_ba_r  <= 2'd0;
                sdram_a_r   <= {3'b010, loop_idx[8:0]}; // A10=1, col=loop_idx
                sdram_dqm_r <= 2'b00;
                sdram_din_r <= pattern(loop_idx);
                sdram_oe    <= 1'b1;
            end
            3: sdram_oe <= 1'b1;  // keep DQ driven during write latency
            2: sdram_dqm_r <= 2'b11;
            1: begin
                // tRP after auto-precharge
                if (loop_idx == 4'd15) begin
                    loop_idx <= 0;
                    state    <= S_READ;
                end else begin
                    loop_idx <= loop_idx + 1;
                    state    <= S_WRITE;
                end
            end
        endcase
    end

    // ---- READ LOOP -------------------------------------------------------
    S_READ: begin
        // Cycle 1: ACTIVE
        sdram_cmd_r <= CMD_ACTIVE;
        sdram_ba_r  <= 2'd0;
        sdram_a_r   <= 13'd0;  // row 0
        state       <= S_WAIT_READ;
        timer       <= 7;
    end
    S_WAIT_READ: begin
        timer <= timer - 1;
        case (timer)
            7: ; // tRCD NOP
            6: begin
                // READ with auto-precharge
                sdram_cmd_r <= CMD_READ;
                sdram_ba_r  <= 2'd0;
                sdram_a_r   <= {3'b010, loop_idx[8:0]}; // A10=1
                sdram_dqm_r <= 2'b00;
            end
            5: ; // CL=2: first NOP after CAS
            4: capture_en <= 1'b1;  // sample data (CL=2 → data valid here)
            3: begin
                // Compare
                if (read_data_cap !== pattern(loop_idx))
                    err_cnt <= (err_cnt == 3'b111) ? 3'b111 : err_cnt + 1;
            end
            2: ; // tRP after auto-precharge
            1: begin
                if (loop_idx == 4'd15) begin
                    pass  <= (err_cnt == 0);
                    state <= S_DONE;
                end else begin
                    loop_idx <= loop_idx + 1;
                    state    <= S_READ;
                end
            end
        endcase
    end

    // ---- DONE: show result, handle periodic refresh ----------------------
    S_DONE: begin
        if (do_ref) begin
            do_ref      <= 0;
            sdram_cmd_r <= CMD_REFRESH;
        end
    end

    default: state <= S_RESET;
    endcase
    end // !btn1_rise

    // ---- LED output ------------------------------------------------------
    led <= {
        hb_cnt[24],            // D7: heartbeat
        (state == S_DONE) & pass, // D6: PASS
        err_cnt,               // D5:3: error count
        state[2:0]             // D2:0: FSM state
    };

end

endmodule
`default_nettype wire
