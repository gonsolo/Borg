# Running on an FPGA

Borg targets two FPGA boards. The **ULX3S** is the primary active development
platform; the **pico-ice** is the companion board used alongside the Tiny Tapeout
ASIC shuttle.

---

## ULX3S (Lattice ECP5-85K) — Primary Target

The ULX3S board carries a Lattice ECP5-85K FPGA with 84K LUTs, 32 Mb SDRAM,
and HDMI output. It is the main bring-up and integration target for the full
Borg SoC.

### Build and Upload

The ULX3S build uses Yosys for synthesis and nextpnr-ecp5 for place-and-route:

```bash
cd fpga/ulx3s
make load    # Synth + P&R + load bitstream to SRAM (openFPGALoader)
make flash   # Write bitstream to config flash (survives power cycle)
make tio     # Open serial console on /dev/ttyUSB0
```

There are also lightweight targets for fast iteration without the full ~10 min
synthesis:

```bash
make minimal-boot   # Build + flash minimal FlashBootLoader test (Hutt + UART only)
```

Layered bring-up bitstreams in `fpga/ulx3s/debug/` isolate individual subsystems
(UART, SDRAM, HDMI) without paying the full SoC synthesis cost.

### Clock Domains

| Domain | Frequency | Used for |
|--------|-----------|----------|
| SoC clock | 25 MHz | Hutt CPU, MemoryController, Borg peripheral |
| HDMI pixel clock | 125 MHz | TMDS serialiser |

---

## pico-ice (Lattice iCE40 UP5K) — ASIC Companion Target

The [pico-ice](https://pico-ice.tinyvision.ai/) board combines a Lattice iCE40
UP5K FPGA with an RP2040 microcontroller. The RP2040 serves as both the
programmer and the host for display output. This target mirrors the constraints
of the Tiny Tapeout ASIC shuttle (4 MHz clock, QSPI flash + PSRAM).

### Build and Upload

```bash
cd fpga
make burn       # Synthesize, place & route, program bitstream
make triangle   # Run the triangle demo
```

### Host Setup (one time per workstation)

Install the udev rules before first use:

```bash
sudo make -C fpga install-udev
```

This installs `fpga/host/99-pico-ice.rules` into `/etc/udev/rules.d/`, which
prevents the kernel's `usb_storage` driver from binding to the pico-ice.

**Why this matters:** When the RP2040's MicroPython USB stack crashes under heavy
PIO/SPI load, it may briefly present as a USB mass storage device. Without this
rule, `usb_storage` binds to it, gets stuck in an uninterruptible kernel sleep
(`state:D`), and **a full system reboot is required** to recover — even replug
won't help. With the rule installed, the device simply re-enumerates as `ttyACM0`
after the RP2040 restarts its USB stack.

> **Note:** If you ever see `ttyACM0` disappear and `mpremote` returns
> `OSError: [Errno 5]`, run `sudo make -C fpga usb-recover` first. If that fails
> (i.e. you see `error -110` in dmesg), only a reboot will fix it — but with the
> udev rule installed this should never happen again.

### Hardware Setup

The pico-ice connects to the host workstation via USB. The RP2040 handles:

1. **Programming the QSPI flash** with the Hutt firmware
2. **Writing input data** (uniforms, resolution) to PSRAM via QPI
3. **Booting the FPGA** — releasing reset, starting the 4 MHz clock
4. **Reading results** — framebuffer data from PSRAM after rendering completes

The QSPI bus is shared between the FPGA and RP2040: the host writes data before
boot, then releases the bus pins so Hutt can execute from flash and access PSRAM.

### The Rendering Loop

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
