// flash_id_test.v — Step 1: read JEDEC ID (0x9F) from onboard flash via USRMCLK.
//
// Expected for ISSI IS25LP128 (the chip on ULX3S):
//   Byte 0 (manufacturer): 0x9D  → LED[7:0]
//   Byte 1 (memory type):  0x60
//   Byte 2 (capacity):     0x18
//
// LED assignment after read completes:
//   LED[7]   = done
//   LED[6]   = manufacturer == 0x9D (ISSI) — lights if USRMCLK works
//   LED[5:0] = capacity byte low 6 bits (0x18 → bits 3,4 = LEDs 3,4 on)
//
// If LED[7] never lights: FSM is stuck (flash not responding at all).
// If LED[7] lights but [6] is off: wrong manufacturer — check LPF / wiring.
// If LED[7,6] both light: USRMCLK + SPI + flash all working. ✓

`default_nettype none
module flash_id_test (
  input        clk_25mhz,
  output [7:0] led,
  output reg   flash_csn   = 1,
  output reg   flash_mosi  = 0,
  input        flash_miso,
  output       flash_wpn,    // WP#   — must be HIGH for reads
  output       flash_holdn   // HOLD# — must be HIGH to not pause flash
);

  assign flash_wpn   = 1;
  assign flash_holdn = 1;

  // ── SPI clock: 25 MHz / 256 ≈ 98 kHz (very conservative) ────────────────
  reg [7:0] clk_div = 0;
  always @(posedge clk_25mhz) clk_div <= clk_div + 1;
  wire spi_clk_raw = clk_div[7] & ~flash_csn;  // idle when CS=1, run when CS=0

  // USRMCLK: route spi_clk_raw to flash MCLK pin.
  // USRMCLKTS=0 (always enabled) — SYSCONFIG MASTER_SPI_PORT=DISABLE must be
  // set in the LPF so the ECP5 doesn't reclaim this pin after configuration.
  USRMCLK usrmclk_inst (
    .USRMCLKI (spi_clk_raw),
    .USRMCLKTS(1'b0)
  ) /* synthesis syn_noprune=1 */;

  // ── Power-up wait: ~335 ms @ 25 MHz ──────────────────────────────────────
  reg [23:0] wait_cnt = 0;
  reg        ready    = 0;
  always @(posedge clk_25mhz)
    if (!ready) {ready, wait_cnt} <= {1'b0, wait_cnt} + 1;

  // ── Edge detection on spi_clk_raw ─────────────────────────────────────────
  reg spi_prev = 0;
  always @(posedge clk_25mhz) spi_prev <= spi_clk_raw;
  wire spi_rise = spi_clk_raw & ~spi_prev;
  wire spi_fall = ~spi_clk_raw & spi_prev;

  // ── SPI FSM ───────────────────────────────────────────────────────────────
  // SPI Mode 0: CPOL=0 CPHA=0 — data set on falling edge, sampled on rising.
  //
  // States:
  //   0 = idle (wait for ready)
  //   1 = sending 0x9F (8 bits, MSB first)
  //   2 = receiving byte 0 (manufacturer)
  //   3 = receiving byte 1 (memory type)
  //   4 = receiving byte 2 (capacity)
  //   5 = done (deassert CS, freeze)

  reg [2:0] state   = 0;
  reg [2:0] bit_cnt = 7;   // counts down 7..0 within each byte
  reg [7:0] mfr_id  = 0;
  reg [7:0] mem_typ = 0;
  reg [7:0] cap_id  = 0;

  localparam CMD_JEDEC = 8'h9F;

  always @(posedge clk_25mhz) begin
    case (state)

      // ── 0: wait then start ────────────────────────────────────────────────
      0: if (ready) begin
        flash_csn  <= 0;                    // assert CS
        flash_mosi <= CMD_JEDEC[7];         // MSB first
        bit_cnt    <= 6;                    // 6 more bits to go after MSB
        state      <= 1;
      end

      // ── 1: transmit CMD_JEDEC (0x9F) ─────────────────────────────────────
      // Update MOSI on falling edge so data is stable for next rising edge.
      1: if (spi_fall) begin
        if (bit_cnt == 0) begin
          flash_mosi <= 0;
          bit_cnt    <= 7;
          state      <= 2;
        end else begin
          bit_cnt    <= bit_cnt - 1;
          flash_mosi <= CMD_JEDEC[bit_cnt - 1];
        end
      end

      // ── 2: receive manufacturer byte ─────────────────────────────────────
      2: if (spi_rise) begin
        mfr_id  <= {mfr_id[6:0], flash_miso};
        if (bit_cnt == 0) begin bit_cnt <= 7; state <= 3; end
        else               bit_cnt <= bit_cnt - 1;
      end

      // ── 3: receive memory type byte ───────────────────────────────────────
      3: if (spi_rise) begin
        mem_typ <= {mem_typ[6:0], flash_miso};
        if (bit_cnt == 0) begin bit_cnt <= 7; state <= 4; end
        else               bit_cnt <= bit_cnt - 1;
      end

      // ── 4: receive capacity byte ──────────────────────────────────────────
      4: if (spi_rise) begin
        cap_id <= {cap_id[6:0], flash_miso};
        if (bit_cnt == 0) state <= 5;
        else               bit_cnt <= bit_cnt - 1;
      end

      // ── 5: done — deassert CS ────────────────────────────────────────────
      5: flash_csn <= 1;

    endcase
  end

  // ── LED output ────────────────────────────────────────────────────────────
  wire done = (state == 5);
  assign led[7]   = done;
  assign led[6]   = done & (mfr_id == 8'h9D);  // 1 = ISSI manufacturer confirmed
  assign led[5:0] = cap_id[5:0];               // capacity 0x18 → LEDs 3,4 on

endmodule
`default_nettype wire
