# Generating the ASIC

The ASIC target is a [wafer.space](https://wafer.space/) multi-project wafer
run on GlobalFoundries' open GF180MCU (180 nm) process, in the **1x1 slot**
(die 3,932 × 5,122 µm, core 3,048 × 4,238 µm). The RTL-to-GDS flow uses
entirely open-source tools.

## What Is Taped Out

The chip is **Borg only**: the shader processor behind a chip-to-chip link,
with no CPU and no memory on the die. `BorgOnlyTop` (asic/wafer/src/) puts
`Borg` behind `BorgLinkSlave`; the host side — a CPU, SDRAM and the matching
`BorgLinkMaster` — sits on an FPGA. Borg's MMIO register access and its
`gpuMem` DRAM traffic both cross the link, as 16-bit flits on 16 data lanes
per direction, with odd parity, credit-based flow control and a training
sequence that locks the beat phase. A `link_narrow` strap halves the lanes at
runtime (the post-silicon recovery path) and `link_fast` doubles the beat
rate; six debug pads expose training and receiver state.

`BorgOnlyTop` carries a lane map for two slots: 1x1 (40 bidir + 12 input-only
pads, the tapeout) and 1x0.5 (46 bidir + 4 input). The configuration is
`BorgConfig.Wafer`: the full Default feature set (FP32 datapath, 4× MSAA,
depth flush, blending, stencil, bilinear filtering) at the slot's sizing.

## The Flow

```
make librelane      # full signoff: GF180MCU, 1x1 slot, several hours
```

This runs [LibreLane](https://github.com/librelane/librelane) (a pinned fork,
see flake.nix) from asic/wafer.space/: synthesis (Yosys), floorplan and padring,
placement, clock tree synthesis, routing, multi-corner STA, and DRC / LVS /
antenna signoff (Magic, KLayout, Netgen). The Verilog comes from
`make generate_verilog_wafer_1x1`.

Configuration lives in `asic/wafer.space/librelane/`: `config.yaml` (the flow),
`slots/slot_1x1.yaml` (die, core and pad ring) and `macros/`. Two settings to
know:

- `CLOCK_PERIOD: 125` — the signoff clock, 8 MHz.
- `PL_TARGET_DENSITY_PCT` — global placement target density.

## Status

The FP32 design has not yet closed signoff: detailed placement fails
(`DPL-0036`) after the post-placement design repair. The cause was measured,
not tuned around: the pad reset is the synchronous reset of the whole core,
and its buffer tree (≈60,000 repeater cells, 2.1 million µm²) concentrates in
a small region that cannot then be legalized. The design fix — reset only
control state, and synchronize and replicate the pad reset per block — is
the next step.

## Verification

```
make test-all                               # Chisel, cocotb, lint, renders
make lint-wafer                             # Verilator lint of BorgOnlyTop1x1
make -C asic/wafer.space sim-link           # link tests through the real padring (RTL)
make -C asic/wafer.space librelane-synth    # LibreLane through Yosys synthesis only (~1.5 h)
make -C asic/wafer.space sim-link-synth     # the same tests on the synthesis netlist
make -C asic/wafer.space sim-link-gl        # the same tests on the post-layout netlist
```

The pad-level suite (asic/wafer.space/cocotb/chip_link_tb.py) drives the chip
through the GF180 pad models with a Python model of the link master, which
also serves Borg's DRAM requests from a memory that stores halfwords the way
the real controller does. It trains the link, checks the lane map in both
directions, the `link_narrow` strap and the debug bus, and runs shader
programs end to end: FP32 STORE/LOAD, ADD/MUL/FNEG/FMA, a rotation shader,
FSTEP and FRCP. `SLOT=1x1` (default) or `SLOT=1x0p5` selects the lane map.

Gate-level simulation is what found the link bring-up bugs fixed in
September 2026: RTL simulation happened to hide them because master and slave
always started at the same beat phase (`BorgLinkProtocolTests` now sweeps the
pin latency instead).

## History: Tiny Tapeout

Borg started on [Tiny Tapeout](https://tinytapeout.com/) as a full SoC — CPU,
QSPI memory controller and GPU — on IHP SG13G2 (130 nm).

- **TTIHP26a** (submitted March 2026): TinyQV CPU + Borg in a 4×2 tile
  (≈260,000 µm²). Git tag `TinyTapeoutIHP26a`.
- **TTIHP26b** (planned for September 2026): Hutt RV32I + Borg in an 8×4 tile.
  The full flow closed on 2026-08-05 (850,851 µm² post-synthesis, 88.1%
  utilization, 2.55 mW at 4 MHz, DRC/LVS/antenna clean), but the target was
  dropped in favour of wafer.space, which fits a larger GPU.

<p align="center">
  <img src="gds_render_small.png" alt="Borg GPU GDS render (Tiny Tapeout, IHP SG13G2)">
</p>

<p align="center">
  <img src="images/placement_annotated.png" alt="Annotated placement clusters">
  <br>
  <em>Global placement of the Tiny Tapeout SoC, annotated with the functional
  modules. Colors reflect the Chisel design blocks.</em>
</p>

The Tiny Tapeout top survives as `QspiSocTop` (hardware/soc/src/), the
harness for the cocotb CPU SoC tests in test/soc/ and for `make lint`.
