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

### RV64 Linux with a tiny Borg (`borg-minimal-linux-borg`)

`make generate_verilog_ulx3s_minimal_linux_borg`; `BorgConfig.Tiny` (FP32, 4 lanes, 1024 bin
tiles, no MSAA, blend, stencil, compute, depth flush or perf counters) beside the RV64
Hutt, scanout off, GPU memory at SDRAM 16 MB and up (the VRAM region bit). nextpnr:
69,948 / 83,640 LUTs (83%), 75 MULT18X18D, 7 DP16KD. The PLL gives 18.75 MHz; Fmax 18.76 MHz,
so there is no margin. One lane renders the cube wrong (derivatives need the 2x2 quad), and
four lanes at 25 MHz reach only 15 to 18 MHz. The limiter is the lane's integer ALU fed
straight from the register BRAM, not the FMA (`fmaStages=4` changes nothing).

Host-free frame: `make -C software/borg replay` links the captured borgvk burst into the
firmware; the simulator (`make -C simulation/verilator minimal-linux-borg-sim`) renders it
bit-exact against `simulation/golden/vkcube_cts_uart_00.ppm`. `scripts/uart_ppm.py` turns the
UART dump (or a `DUMP_*` simulator dump) into a picture.
