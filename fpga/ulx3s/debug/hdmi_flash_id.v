// hdmi_flash_id.v — reads flash JEDEC ID via USRMCLK, shows 3 color bars on HDMI.
//
// Screen layout (640x480):
//   Left third  (R):  red intensity   = manufacturer byte (0x9D = bright red)
//   Middle third (G): green intensity = memory type byte  (0x60 = medium green)
//   Right third  (B): blue intensity  = capacity byte     (0x18 = dim blue)
//
// Expected: bright red | medium green | dim blue  → flash reads correctly.
//
// NOTE: This bitstream MUST be written to flash (not just SRAM) so that
// CONFIG_MODE=SPI_SERIAL takes effect and the flash stays in standard SPI mode.

`default_nettype none
module hdmi_flash_id (
  input        clk_25mhz,
  output [3:0] gpdi_dp,
  output [7:0] led,
  output       flash_csn,
  output       flash_mosi,
  input        flash_miso,
  output       flash_wpn,
  output       flash_holdn,
  output       wifi_en
);

  assign wifi_en     = 0;  // Hold ESP32 in reset — keeps SPI bus quiet
  assign flash_wpn   = 1;
  assign flash_holdn = 1;

  // ── PLL: 25 MHz → 125 MHz (shift) + 25 MHz (pixel) ──────────────────────
  wire [3:0] clocks;
  wire clk_shift = clocks[0];   // 125 MHz for TMDS DDR
  wire clk_pixel = clocks[1];   //  25 MHz pixel clock

  ecp5pll #(
    .in_hz   (25_000_000),
    .out0_hz (125_000_000),
    .out1_hz  (25_000_000)
  ) pll_inst (
    .clk_i  (clk_25mhz),
    .clk_o  (clocks),
    .locked ()
  );

  // ── Flash SPI via USRMCLK ────────────────────────────────────────────────
  // SPI clock: 25 MHz / 256 ≈ 97 kHz (very conservative, plenty of margin)
  // Clock held low while CS is deasserted (SPI mode 0).
  reg [7:0] clk_div    = 0;
  reg       flash_csn_r = 1;
  reg       flash_mosi_r = 0;
  assign flash_csn  = flash_csn_r;
  assign flash_mosi = flash_mosi_r;

  always @(posedge clk_25mhz) begin
    if (flash_csn_r) clk_div <= 0;
    else             clk_div <= clk_div + 1;
  end

  wire spi_clk = clk_div[7];
  USRMCLK usrmclk_inst (.USRMCLKI(spi_clk), .USRMCLKTS(1'b0))
    /* synthesis syn_noprune=1 */;

  reg spi_prev = 0;
  always @(posedge clk_25mhz) spi_prev <= spi_clk;
  wire spi_rise =  spi_clk & ~spi_prev;
  wire spi_fall = ~spi_clk &  spi_prev;

  // ── Power-on delay ≈ 670 ms ──────────────────────────────────────────────
  reg [23:0] wait_cnt = 0;
  reg        ready    = 0;
  always @(posedge clk_25mhz)
    if (!ready) {ready, wait_cnt} <= {1'b0, wait_cnt} + 1;

  // ── SPI state machine ─────────────────────────────────────────────────────
  // TX: shift command byte MSB-first on falling edges.
  // RX: sample MISO on rising edges.
  localparam S_IDLE     = 3'd0;
  localparam S_CMD_CS   = 3'd1;
  localparam S_CMD_BITS = 3'd2;
  localparam S_RX_MFR   = 3'd3;
  localparam S_RX_TYP   = 3'd4;
  localparam S_RX_CAP   = 3'd5;
  localparam S_DONE     = 3'd6;

  reg [2:0] state   = S_IDLE;
  reg [2:0] bit_cnt = 0;
  reg [7:0] mfr_id  = 0;
  reg [7:0] mem_typ = 0;
  reg [7:0] cap_id  = 0;
  reg       spi_done = 0;

  always @(posedge clk_25mhz) begin
    case (state)
      S_IDLE: if (ready) state <= S_CMD_CS;

      S_CMD_CS: begin
        flash_csn_r  <= 0;
        flash_mosi_r <= 1'b1;  // MSB of 0x9F
        bit_cnt      <= 7;
        state        <= S_CMD_BITS;
      end

      S_CMD_BITS: if (spi_fall) begin
        if (bit_cnt == 0) begin
          flash_mosi_r <= 0;
          bit_cnt      <= 7;
          state        <= S_RX_MFR;
        end else begin
          flash_mosi_r <= (8'h9F >> (bit_cnt - 1)) & 1'b1;
          bit_cnt      <= bit_cnt - 1;
        end
      end

      S_RX_MFR: if (spi_rise) begin
        mfr_id <= {mfr_id[6:0], flash_miso};
        if (bit_cnt == 0) begin bit_cnt <= 7; state <= S_RX_TYP; end
        else bit_cnt <= bit_cnt - 1;
      end

      S_RX_TYP: if (spi_rise) begin
        mem_typ <= {mem_typ[6:0], flash_miso};
        if (bit_cnt == 0) begin bit_cnt <= 7; state <= S_RX_CAP; end
        else bit_cnt <= bit_cnt - 1;
      end

      S_RX_CAP: if (spi_rise) begin
        cap_id <= {cap_id[6:0], flash_miso};
        if (bit_cnt == 0) state <= S_DONE;
        else bit_cnt <= bit_cnt - 1;
      end

      S_DONE: begin
        flash_csn_r <= 1;
        spi_done    <= 1;
      end
    endcase
  end

  // ── LEDs ──────────────────────────────────────────────────────────────────
  // When done: show manufacturer byte (expect 10011101 = 0x9D for ISSI)
  // D6 = 1 only if mfr_id == 0x9D
  assign led[7]   = spi_done;
  assign led[6]   = spi_done & (mfr_id == 8'h9D);
  assign led[5:0] = spi_done ? cap_id[5:0] : 6'b0;

  // ── VGA timing 640x480@60 ─────────────────────────────────────────────────
  wire [7:0] vga_r, vga_g, vga_b;
  wire vga_hsync, vga_vsync, vga_blank;

  vga #(
    .c_resolution_x(640),
    .c_hsync_front_porch(16), .c_hsync_pulse(96), .c_hsync_back_porch(48),
    .c_resolution_y(480),
    .c_vsync_front_porch(10), .c_vsync_pulse(2),  .c_vsync_back_porch(33),
    .c_bits_x(10), .c_bits_y(10)
  ) vga_inst (
    .clk_pixel(clk_pixel), .clk_pixel_ena(1'b1),
    .test_picture(1'b0),
    .vga_r(vga_r), .vga_g(vga_g), .vga_b(vga_b),
    .vga_hsync(vga_hsync), .vga_vsync(vga_vsync), .vga_blank(vga_blank)
  );

  // ── Pixel coordinate counter ──────────────────────────────────────────────
  reg [9:0] px = 0;
  always @(posedge clk_pixel) begin
    if (vga_hsync) px <= 0;
    else           px <= px + 1;
  end

  // ── Color bars: R=mfr_id, G=mem_typ, B=cap_id ────────────────────────────
  wire [7:0] bar_r = vga_blank ? 8'd0 : (px < 213                  ? mfr_id  : 8'd0);
  wire [7:0] bar_g = vga_blank ? 8'd0 : (px >= 213 && px < 426     ? mem_typ : 8'd0);
  wire [7:0] bar_b = vga_blank ? 8'd0 : (px >= 426                 ? cap_id  : 8'd0);

  // ── VGA → TMDS ────────────────────────────────────────────────────────────
  wire [1:0] tmds_clock, tmds_red, tmds_green, tmds_blue;
  vga2dvid #(.c_ddr(1'b1), .c_shift_clock_synchronizer(1'b0)) vga2dvid_inst (
    .clk_pixel(clk_pixel), .clk_shift(clk_shift),
    .in_red(bar_r), .in_green(bar_g), .in_blue(bar_b),
    .in_hsync(vga_hsync), .in_vsync(vga_vsync), .in_blank(vga_blank),
    .out_clock(tmds_clock), .out_red(tmds_red), .out_green(tmds_green), .out_blue(tmds_blue)
  );

  ODDRX1F ddr_clk   (.D0(tmds_clock[0]), .D1(tmds_clock[1]), .Q(gpdi_dp[3]), .SCLK(clk_shift), .RST(0));
  ODDRX1F ddr_red   (.D0(tmds_red  [0]), .D1(tmds_red  [1]), .Q(gpdi_dp[2]), .SCLK(clk_shift), .RST(0));
  ODDRX1F ddr_green (.D0(tmds_green[0]), .D1(tmds_green[1]), .Q(gpdi_dp[1]), .SCLK(clk_shift), .RST(0));
  ODDRX1F ddr_blue  (.D0(tmds_blue [0]), .D1(tmds_blue [1]), .Q(gpdi_dp[0]), .SCLK(clk_shift), .RST(0));

endmodule
`default_nettype wire
