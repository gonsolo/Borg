// JEDEC ID reader — D07 always blinks to prove FPGA is running.
// After ~40ms: D6:0 show JEDEC bytes from PMOD flash.
// Expect: 0xEF=Winbond → D6,D5,D4,D3,D2,D1,D0 = 1,1,0,1,1,1,1 (lower 7 bits of 0xEF)
// Press FIRE1 to cycle bytes.
module pmod_test (
  input  clk_25mhz,
  input  rst_n,
  input  [5:0] btn,
  output [7:0] led,
  output pmod_cs0, pmod_cs1, pmod_cs2,
  output pmod_sck, pmod_sd0, pmod_sd2, pmod_sd3,
  input  pmod_sd1
);
  assign pmod_cs1 = 1'b1;
  assign pmod_cs2 = 1'b1;
  assign pmod_sd2 = 1'b0;
  assign pmod_sd3 = 1'b0;

  // Always-blinking heartbeat counter
  reg [24:0] cnt = 0;
  always @(posedge clk_25mhz) cnt <= cnt + 1;

  // SPI clock ~390kHz
  reg [5:0]  clk_div = 0;
  reg        spi_clk = 0;
  always @(posedge clk_25mhz) begin
    clk_div <= clk_div + 1;
    if (clk_div == 6'd31) spi_clk <= ~spi_clk;
  end

  localparam WAIT=0, START=1, SEND=2, RECV=3, DONE=4;
  reg [2:0]  state   = WAIT;
  reg [23:0] delay   = 0;
  reg [4:0]  bit_cnt = 0;
  reg [7:0]  shift   = 8'h9F;
  reg [23:0] jedec   = 0;
  reg        cs_n    = 1;
  reg        sck_en  = 0;
  reg        mosi    = 0;
  reg        prev    = 0;

  always @(posedge clk_25mhz) begin
    prev <= spi_clk;
    case (state)
      WAIT:  begin cs_n<=1; sck_en<=0; delay<=delay+1; if(delay==24'd1_000_000) state<=START; end
      START: begin cs_n<=0; bit_cnt<=0; shift<=8'h9F; sck_en<=1; state<=SEND; end
      SEND:  begin
        if (!spi_clk && prev) begin   // falling edge: update MOSI for next bit
          mosi<=shift[7]; shift<={shift[6:0],1'b0}; bit_cnt<=bit_cnt+1;
          if(bit_cnt==7) begin bit_cnt<=0; state<=RECV; end
        end
      end
      RECV:  begin
        if (spi_clk && !prev) begin   // rising edge
          jedec<={jedec[22:0],pmod_sd1}; bit_cnt<=bit_cnt+1;
          if(bit_cnt==23) state<=DONE;
        end
      end
      DONE:  begin cs_n<=1; sck_en<=0; end
    endcase
  end

  assign pmod_cs0 = cs_n;
  assign pmod_sck = sck_en ? spi_clk : 1'b0;
  assign pmod_sd0 = mosi;

  // Button cycle
  reg [1:0] show = 0;
  reg bp = 0;
  always @(posedge clk_25mhz) begin
    bp <= btn[0];
    if (btn[0] && !bp) show <= (show==2) ? 0 : show+1;
  end

  wire [7:0] disp = (show==0) ? jedec[23:16] : (show==1) ? jedec[15:8] : jedec[7:0];

  // D07 = heartbeat; D6:3 = top nibble of selected JEDEC byte; D2:0 = state
  // FIRE1 cycles through bytes: 0=manufacturer(0xEF), 1=type(0x40), 2=capacity(0x18)
  wire [7:0] selected = (show==0) ? jedec[23:16] : (show==1) ? jedec[15:8] : jedec[7:0];
  assign led = {cnt[24], selected[6:3], state[2:0]};

endmodule
