# Borg — An Open-Source GPU

<!-- markdownlint-disable MD033 -->
<p align="center">
  <img src="gds_render_small.png" alt="Borg GPU GDS Render">
</p>
<!-- markdownlint-enable MD033 -->

## Quick Start

```bash
# Clone and enter the reproducible toolchain:
git clone --recurse-submodules <repo>
cd Borg
nix develop          # or: direnv allow (if using direnv)

# Run all unit tests (~2 min):
make test-all

# Cycle-accurate simulation (arcilator is fastest):
make -C simulation/arcilator vkcube_headless   # headless, checks golden
make -C simulation/arcilator vkcube_gui        # interactive Pygame window

# Build and flash the ULX3S FPGA:
cd fpga/ulx3s && make load   # ~10 min first run; subsequent: ~3 min
```

## Known Issues / Limitations

- **Resolution**: the current demo renders at 128×128. 800×480 HDMI is wired but
  the SDRAM bandwidth at 25 MHz tops out at ~15–20 fps at that resolution; the
  demo targets 128×128 for the HPG 2026 deadline.
- `BorgConfig.Default` (ULX3S) is the primary FPGA target.
- **cocotb gate-level tests**: `test-cocotb-soc-core-gl` and
  `test-cocotb-soc-borg-gl` require a synthesized netlist; they are skipped in CI
  unless `make gds-ihp` has been run first.

## Reading Order

The chapters are largely self-contained, but some paths work better than others
depending on your goal:

- **"I want to understand the architecture"** — Read [07_tbr](07_tbr.md) first
  (the two-pass TBR gives the big picture), then [01_shader_processor](01_shader_processor.md),
  then [03_software_driver](03_software_driver.md).
- **"I want to build or modify the hardware"** — Start with [CLAUDE.md](../CLAUDE.md)
  (build system) and [A0_roadmap](A0_roadmap.md) (where things stand), then
  dive into the relevant chapter.
- **"I want to understand the compiler"** — Read [02_compiler](02_compiler.md),
  which assumes familiarity with [01_shader_processor](01_shader_processor.md).
- **"I'm unfamiliar with the terminology"** — See the [Glossary](glossary.md)
  for canonical names (BorgCore, tile buffer, SPIR-B, etc.).

## Table of Contents

0. [Introduction](00_introduction.md) — Motivation and project overview
1. [The Borg Shader Processor](01_shader_processor.md) — FP16 FMA, registers, instruction memory
2. [The Shader Compiler](02_compiler.md) — SPIR-V → pseudo-assembly → SPIR-B pipeline
3. [The Software Driver](03_software_driver.md) — Shader pipeline, z-buffer, texturing
4. [Running on an FPGA](04_fpga.md) — ULX3S FPGA target
5. [Generating the ASIC](05_asic.md) — RTL-to-GDS flow, configuration, verification
6. [Simulation](06_simulation.md) — Verilator, Arcilator, and interactive viewing
7. [Tile-Based Rendering](07_tbr.md) — Two-pass TBR, BorgBinner, BorgSequencer FSM, DRAM layout

### Appendices

1. [Development Roadmap](A0_roadmap.md) — Phases, tile budget, hardware resources
2. [Gap Analysis and Vulkan Strategy](A2_gap_analysis.md) — vkcube, SuperTuxKart, "No Graphics API", Vulkan ICD plan
3. [The Hutt CPU](A3_hutt_cpu.md) — Multi-cycle RV32I core with Decoupled buses
4. [Bibliography](A1_bibliography.md) — References and further reading
5. [Architectural Tricks](A8_architectural_tricks.md) — 15 tricks for minimizing area
6. [Project Poster](A9_poster.md) — Academic poster summarizing the Borg GPU architecture
7. [Glossary](glossary.md) — Canonical terminology for hardware components, shader formats, and tools
