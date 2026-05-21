// bare_spi_test.v — absolute minimum SPI flash test.
// No PLL, no HDMI, no complex FSM. Just bang out 0x9F and show MISO on LEDs.

`default_nettype none
module bare_spi_test (
  input        clk_25mhz,
  output [7:0] led,
  output       flash_csn,
  output       flash_mosi,
  input        flash_miso,
  output       flash_wpn,
  output       flash_holdn,
  output       wifi_en
);

  assign wifi_en     = 0;
  assign flash_wpn   = 1;
  assign flash_holdn = 1;

  // Very slow SPI clock: 25 MHz / 2^12 = ~6 kHz
  reg [11:0] clk_div = 0;
  reg cs_n  = 1;
  reg mosi  = 0;
  assign flash_csn  = cs_n;
  assign flash_mosi = mosi;

  always @(posedge clk_25mhz) begin
    if (cs_n) clk_div <= 0;
    else      clk_div <= clk_div + 1;
  end

  wire spi_clk = clk_div[11];
  USRMCLK u (.USRMCLKI(spi_clk), .USRMCLKTS(1'b0))
    /* synthesis syn_noprune=1 */;

  reg prev = 0;
  always @(posedge clk_25mhz) prev <= spi_clk;
  wire rise = spi_clk & ~prev;
  wire fall = ~spi_clk & prev;

  // Startup delay ~670ms
  reg [23:0] wcnt = 0;
  reg rdy = 0;
  always @(posedge clk_25mhz)
    if (!rdy) {rdy, wcnt} <= {1'b0, wcnt} + 1;

  // Simple FSM
  reg [1:0] st = 0;
  reg [3:0] bc = 0;
  reg [7:0] rx = 0;
  reg done = 0;

  always @(posedge clk_25mhz) begin
    case (st)
      0: if (rdy) begin cs_n <= 0; mosi <= 1; bc <= 0; st <= 1; end
      1: if (fall) begin
           bc <= bc + 1;
           if (bc < 7) mosi <= (8'h9F >> (6 - bc[2:0])) & 1;
           else begin mosi <= 0; bc <= 0; st <= 2; end
         end
      2: if (rise) begin
           rx <= {rx[6:0], flash_miso};
           bc <= bc + 1;
           if (bc == 7) begin cs_n <= 1; done <= 1; st <= 3; end
         end
      3: ;
    endcase
  end

  assign led = done ? rx : {flash_miso, spi_clk, ~cs_n, rdy, 4'b0};

endmodule
`default_nettype wire
