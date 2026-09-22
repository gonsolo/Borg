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

- `CLOCK_PERIOD: 200` — the signoff clock, 5 MHz.
- `PL_TARGET_DENSITY_PCT` — global placement target density.

## Status

**As of 2026-09-22 (run `msaa-jump`): physical implementation is proven
correct for the first time ever on a Borg `chip_top`. Timing is not yet
closed.**

The design now includes multi-pass MSAA (one live tile-buffer sample plus an
accumulator instead of four resident samples, −9.8% of Borg's area), a fix
for the routing-demand regression that first attempt introduced, and a
register stage pipelining the sequencer's MMIO configuration path — the
combination that finally got a full run through place, route, and signoff
checking:

| check | result |
|---|---|
| Global routing | 0 congestion, 0 NDR strips, demand 1,478,023 (best of every variant tried) |
| Detailed routing | **0 violations** (trajectory: 150,111 → 61,903 → 56,321 → 5,470 → ⋯ → 0 over 13 iterations) |
| Magic DRC | **0 violations** |
| KLayout DRC | **0 violations** |
| Magic ↔ KLayout GDS XOR | match |
| Netgen LVS | **"Circuits match uniquely"** — 187,189 devices, 190,491 nets |

That is routing, DRC and LVS all independently clean — the furthest any Borg
ASIC attempt has reached. The earlier antenna-repair strategy (diode
insertion) was replaced with jumper-only repair
(`GRT_ANTENNA_REPAIR_JUMPER_ONLY` / `DRT_ANTENNA_REPAIR_JUMPER_ONLY`, both on
by default now): diodes are real cells and 134 of them were enough to push
routing demand +3.8% and turn a congestion-free route into a failing one.
Jumpers hop a net up a layer and back instead of inserting cells, so they
cost no routing demand.

**What is not yet closed, on that same run:**

1. **Timing.** Post-RCX (real extracted parasitics) STA at 200 ns fails
   setup, hold, max-slew and max-cap together at the slow/hot/low corners —
   see the table in `config.yaml` next to `CLOCK_PERIOD`. Worst case
   (`max_ss_125C_3v00`): setup WNS −13.02 ns, TNS −246.36 ns, 28 violating
   endpoints. This looks like a genuinely marginal design at this clock
   period rather than one late path, since all four checks fail together
   across most corners.
2. **268 KLayout antenna errors**, found by the GDS-geometry-level antenna
   check that runs *after* fill insertion and sealring. This is separate
   from the jumper-based antenna repair above, which runs earlier in the
   flow (before fill/sealring add their own metal) and did converge clean.

LibreLane defers both of these and only reports them in a final summary at
the very end of the run — a run reaching GDS/DRC/LVS does not by itself mean
signoff passed; always check the `Checker.SetupViolations` /
`Checker.HoldViolations` / `Checker.MaxSlewViolations` /
`Checker.MaxCapViolations` / `Checker.KLayoutAntenna` results specifically.

A complete `final/` view set (GDS, DEF, LIB, netlists, SPICE, SPEF) is still
written even when these deferred checks fail, so the artifact from a run
like this is real and usable for further analysis even though the run is not
a clean signoff.

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
