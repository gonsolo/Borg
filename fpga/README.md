# Running Borg on a pico-ice

* Setup the FPGA as described here:
  https://github.com/TinyTapeout/ttsky25a-tinyQV/tree/main/fpga/pico-ice
* Run ```make burn```: This should automatically call yosys, nextpnr-ice40 and icepack to create
  a FPGA bitstream file.
* Run ```tio -b 115200 /dev/ttyACM1``` in a second terminal. Here you can see the output.
* Run ```make run``` to execute a program (hello.bin) on the TinyQV processor.
  You should see some output on the second terminal.

# Look out

The clock has to be set in four places, otherwise only garbage will be seen in tio:

* ```set_frequency clk 4``` in pico_ice.pcf.
* ```--freq 4``` in the Makefile when running nextpnr-ice40.
* ```localparam CLOCK_MHZ = 4;``` in pico_ice.v .

Not strictly necessary:

* ```env: TT_FPGA_FREQ: 4``` in ../.github/workflows/fpga.yaml.
