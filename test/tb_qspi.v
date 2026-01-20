`default_nettype none `timescale 1ns / 100ps

/* This testbench just instantiates the module and makes some convenient wires
   that can be driven / tested by the cocotb test.py.
*/
module tb_qspi ();

  // Wire up the inputs and outputs:
  reg clk;
  reg rst_n;
  reg ena;
  reg [7:0] ui_in_base;
  wire [7:0] ui_in;
  reg [7:0] uio_in;
  wire [7:0] uo_out;
  wire [7:0] uio_out;
  wire [7:0] uio_oe;

  wire [3:0] qspi_data_in;
  reg [2:0] latency_cfg;
  assign {uio_in[5:4], uio_in[2:1]} = rst_n ? qspi_data_in : {1'b0, latency_cfg};

  wire [3:0] qspi_data_out = {uio_out[5:4], uio_out[2:1]};
  wire [3:0] qspi_data_oe  = {uio_oe[5:4],  uio_oe[2:1]};
  wire qspi_clk_out = uio_out[3];
  wire qspi_flash_select = uio_out[0];
  wire qspi_ram_a_select = uio_out[6];
  wire qspi_ram_b_select = uio_out[7];

  wire spi_miso = ui_in_base[2];
  assign ui_in[2] = spi_miso;
  wire spi_cs = uo_out[4];
  wire spi_sck = uo_out[5];
  wire spi_mosi = uo_out[3];
  wire spi_dc = uo_out[2];

  wire uart_tx = uo_out[0];
  wire uart_rts = uo_out[1];
  wire debug_uart_tx = uo_out[6];
  wire uart_rx = ui_in_base[7];
  assign ui_in = {uart_rx, ui_in_base[6:3], spi_miso, ui_in_base[1:0]};

`ifdef GL_TEST
  wire VPWR = 1'b1;
  wire VGND = 1'b0;
`endif

  // Replace tt_um_example with your module name:
  tt_um_tt_tinyQV user_project (

      // Include power ports for the Gate Level test:
`ifdef GL_TEST
      .VPWR(VPWR),
      .VGND(VGND),
`endif

      .ui_in  (ui_in),    // Dedicated inputs
      .uo_out (uo_out),   // Dedicated outputs
      .uio_in (uio_in),   // IOs: Input path
      .uio_out(uio_out),  // IOs: Output path
      .uio_oe (uio_oe),   // IOs: Enable path (active high: 0=input, 1=output)
      .ena    (ena),      // enable - goes high when design is selected
      .clk    (clk),      // clock
      .rst_n  (rst_n)     // not reset
  );

  // Simulate latency
  wire [3:0] buffered_qspi_data;
  reg [19:0] data_buffer;
  always @(posedge clk) begin
    data_buffer <= {data_buffer[15:0], buffered_qspi_data};
  end
  assign qspi_data_in = (latency_cfg < 1) ? buffered_qspi_data :
                        data_buffer[(latency_cfg - 1) * 4 +:4];

  // Simulated QSPI PMOD
  sim_qspi_pmod qspi (
    .qspi_data_in(qspi_data_out & qspi_data_oe),
    .qspi_data_out(buffered_qspi_data),
    .qspi_clk(qspi_clk_out),

    .qspi_flash_select(qspi_flash_select),
    .qspi_ram_a_select(qspi_ram_a_select),
    .qspi_ram_b_select(qspi_ram_b_select)
  );

  defparam qspi.INIT_FILE = `PROG_FILE;

endmodule
