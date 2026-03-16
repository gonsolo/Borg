# Running on an FPGA

The design runs on a [pico-ice](https://pico-ice.tinyvision.ai/) board, which
combines a Lattice iCE40 UP5K FPGA with an RP2040 microcontroller. The RP2040
serves as both the programmer and the host for display output.

## Build and Upload

The FPGA build uses Yosys for synthesis and nextpnr for place-and-route,
targeting the iCE40 UP5K:

```
cd fpga
make burn       # Synthesize, place & route, program bitstream
make triangle   # Run the triangle demo
```

## Hardware Setup

The pico-ice connects to the host workstation via USB. The RP2040 handles:

1. **Programming the QSPI flash** with the TinyQV firmware
2. **Writing input data** (uniforms, resolution) to PSRAM via QPI
3. **Booting the FPGA** — releasing reset, starting the 4 MHz clock
4. **Reading results** — framebuffer data from PSRAM after rendering completes

The QSPI bus is shared between the FPGA and RP2040: the host writes data before
boot, then releases the bus pins so TinyQV can execute from flash and access PSRAM.

## The Rendering Loop

The host script (`triangle.py`) orchestrates the full cycle:

1. Write framebuffer dimensions to PSRAM
2. Program firmware and boot the FPGA
3. Generate a 4 MHz clock via PWM
4. Wait for rendering to complete (~10 seconds at 4 MHz)
5. Stop the clock, reset the FPGA
6. Read back the framebuffer from PSRAM via QPI PIO
7. Write the result as a PPM image file

The RP2040's PIO (Programmable I/O) hardware generates the precise QPI timing
needed for PSRAM access — standard SPI would be too slow for framebuffer readback.
