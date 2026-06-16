# Running on an FPGA

The **ULX3S** (Lattice ECP5-85K) is the primary FPGA development and demo target
for the Borg SoC.

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
