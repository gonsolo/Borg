# Running Borg on a pico-ice

* Setup the FPGA as described here:
  https://github.com/TinyTapeout/ttsky25a-tinyQV/tree/main/fpga/pico-ice
* Run ```make burn```: This should automatically call yosys, nextpnr-ice40 and icepack to create
  a FPGA bitstream file.
* Run ```make triangle``` to run the test on the pico-ice. This includes uploading the Micropython
  to the RP2040 on the pico-ice and running it. The program will then upload the vertex shader and vertex
  data to the FPGA and run the vertex shader there. The result will be a little animation of a white triangle
  on a black background.

# Look out

The clock has to be set in four places, otherwise only garbage will be seen in tio:

* ```set_frequency clk 12``` in pico_ice.pcf.
* ```--freq 12``` in the Makefile when running nextpnr-ice40.
* ```localparam CLOCK_MHZ = 12;``` in pico_ice.v .

Not strictly necessary:

* ```env: TT_FPGA_FREQ: 12``` in ../.github/workflows/fpga.yaml.
