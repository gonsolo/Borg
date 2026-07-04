# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Borg is an open-source GPU: a small RISC-V CPU driving the **Borg FP16 shader processor** as an MMIO peripheral. The same Chisel source targets three back ends:

- **ASIC** via Tiny Tapeout (IHP SG13G2) — `asic/tt/`
- **ULX3S** FPGA (Lattice ECP5-85K) — `fpga/ulx3s/`

The CPU has been **rewritten from TinyQV to a new core called Hutt** (`hardware/hutt/src/`). The `hardware/tinyqv/` directory no longer exists; do not recreate it or its protocol.

## Build system layout

- **Top-level `Makefile`** orchestrates Chisel→Verilog emission, cocotb tests, lint, GDS, the docs book, and SystemRDL→Chisel register generation.
- **Mill** (`build.mill` + per-directory `package.mill`) drives Scala/Chisel compilation. `BorgModule` (in `build.mill`) is the shared trait — Scala 2.13, Chisel 7.11. Modules are organized under `hardware/{borg,hutt,memory,peri,soc,hardfloat}`, `fpga/ulx3s/soc`, and `asic/tt`.
- **Nix** (`flake.nix`) provides the full reproducible toolchain (firtool, Yosys, nextpnr, OpenROAD/LibreLane, RISC-V GCC, cocotb, PeakRDL, etc.). Enter it with `nix develop` before running anything below.
- **Per-board sub-Makefiles** (`fpga/ulx3s/Makefile`, `simulation/{verilator,arcilator}/Makefile`, `test/soc/Makefile`, `software/Makefile`) own their flow. The top Makefile forwards to them.

Verilog emission is gated by a stamp file (`.verilog_stamp`, `.verilog_stamp_ulx3s`) — Mill only re-runs when Scala/RDL sources change.

## Common commands

```bash
# Tests
make test-all                   # quiet runner with ✓/✗ per suite (scripts/test_runner.py)
make test-chisel-borg           # Chisel unit tests for Borg FPU/pipeline
make test-chisel-core           # Chisel CPU tests — runs mill hardware.hutt.test (the Hutt core)
make test-cocotb-soc-core-rtl   # cocotb RTL tests for the CPU SoC — 5 tests, all passing (commit 1681e55)
make test-cocotb-soc-borg-rtl   # cocotb RTL tests for the Borg peripheral
make test-cocotb-soc-core-gl    # gate-level (post-synth) variants
make test-cocotb-soc-borg-gl

# Run a single Chisel test class or method (via Mill testOnly + utest selector):
mill hardware.borg.test.testOnly borg.BorgTests
mill hardware.borg.test.testOnly borg.BorgTests -- borg.BorgTests.hw_flusher_autonomous
#   ^class glob               ^class to load        ^full utest dotted path after --

# Verilog generation (Chisel → SystemVerilog via firtool)
make generate_verilog                  # ASIC/TT target (CLOCK_MHZ=4)
make generate_verilog_ulx3s            # ULX3S full SoC (CLOCK_MHZ=25)
make generate_verilog_ulx3s_minimal    # MinimalSoC: Hutt + UART only, no Borg — fast iteration

# Lint
make lint                       # verilator --lint-only against the emitted ASIC Verilog

# ASIC GDS
make gds-sky130                 # Sky130 via LibreLane/OpenROAD
make gds-ihp                    # IHP SG13G2

# Simulation (cycle-accurate C++ with Pygame UI)
make -C simulation/verilator vkcube_gui
make -C simulation/arcilator vkcube_gui   # faster (CIRCT arcilator backend)

# FPGA — ULX3S (use the board-specific Makefile)
cd fpga/ulx3s && make load           # synth + P&R + load to SRAM (openFPGALoader)
cd fpga/ulx3s && make flash          # write to config flash
cd fpga/ulx3s && make tio            # open serial console on /dev/ttyUSB0
cd fpga/ulx3s && make minimal-boot   # build + flash minimal FlashBootLoader test
```

When a build needs the SystemRDL-generated register block, the top Makefile runs `make rdl` as an order-only dep — usually you don't need to invoke it directly.

## Architecture (the parts that need cross-file reading)

### CPU ↔ peripheral fabric (Hutt + MemoryController + SoC)

`hardware/hutt/Hutt.scala` is a clean multi-cycle RV32I/RV64I core (parameterized via the `xlen` constructor arg, default 32) with **Decoupled** instruction and data buses (`HuttInstrBus`, `HuttBus` in `HuttBus.scala`). **Current split by target:** the ASIC/TT tapeout (`asic/tt/src/TTTop.scala`) uses the RV32I default; the ULX3S FPGA path (`fpga/ulx3s/soc/src/ULX3S.scala`, the active demo target) overrides `xlen = 64` — RV64IMAC with M-mode/S-mode privilege levels, Sv39 MMU, and CLINT, laying groundwork for a Linux boot (not yet attempted; see `software/opensbi/`, `software/linux/`). `hardware/soc/src/MinimalSoC.scala` (the slim Hutt + UART + MemoryController harness used for ULX3S/HDMI/UART bring-up) still instantiates Hutt at its RV32I default. The CPU's data bus is decoded against `SoCDecode` constants to route MMIO between SoC inline registers, the user peripheral router, and the Borg peripheral bus.

`hardware/memory/src/MemoryController.scala` arbitrates the instruction port, the CPU data port, and the GPU's `gpuMem` port across QSPI flash (`QspiBackend`) and SDRAM (`SdramBackend`) backends, with a `FlashBootLoader` for cold-boot copy-in. The GPU port can be tied off (default in `MinimalSoCLogic.wireGpuMem`) or driven by HDMI scanout in bring-up harnesses.

### Borg shader processor (the actual GPU)

Lives in `hardware/borg/src/`. `Borg.scala` is the top, with a 4-cycle FP16 FMA pipeline (Berkeley HardFloat via `hardware/hardfloat/`), 32 FP16 registers, instruction memory, a hardware FP16 reciprocal (`Fp16Rcp.scala` + `rcp_lut.hex` + `coord_lut.hex`), tile buffer with Z (`BorgTileBuffer.scala`), texture unit with Morton encoding (`BorgTextureUnit.scala`), rasterizer (`BorgRasterizer.scala`), and a 2-entry async command FIFO (`BorgCommandFIFO.scala`). The CPU pokes it via the MMIO register block defined in SystemRDL.

### SystemRDL → Chisel + C headers

`hardware/rdl/*.rdl` is the **single source of truth** for the MMIO register layout. `make rdl` runs:

1. `hardware/rdl/validate_rdl.py` (PeakRDL).
2. `hardware/rdl/generate.py` which invokes the in-tree **`PeakRDL-chisel`** submodule to emit `hardware/borg/src/generated/BorgGpuRegs.scala` and emits a C header to `out/hardware/borg/rdl/borg_regs.h`.

If you add or change a register, edit the `.rdl`, then re-run `make rdl` (or any target that depends on `.verilog_stamp`).

### Firmware

`software/tinyqv/` is the runtime/startup/UART/printf for the on-CPU firmware (`start.s`, `runtime.c`, `nanoprintf.h`, `tinyQV.a` archive). `software/borg/` contains the GPU driver, SPIR-B shader format, math, rasterizer, and `borg_kernel.c` — a thin render kernel that boots, drains borgvk wire packets (0xAD MVP / 0xAE geometry / 0xAF texture / 0xB0 shaders), and drives the autonomous TBR hardware. No hardcoded geometry, shaders, or texture; all content is uploaded at runtime by borgvk / cube.c.

### ULX3S bring-up harnesses (the recent active work)

Multiple bitstream targets live in `fpga/ulx3s/soc/src/`: `ULX3S.scala` (full SoC), `ULX3SMinimal.scala` (Hutt + UART, ~1.5 min build), `HdmiTestPattern.scala`, `HdmiSdramTest.scala`, `CpuSdramHdmiTest.scala`, `SdramTest.scala`, `BootUartTest.scala`, plus per-target `Main` objects emitted via `make generate_verilog_*` recipes. `fpga/ulx3s/debug/` holds further small fast-iteration bitstreams. Use these to isolate bring-up issues without paying the full SoC's ~10 min synthesis cost.

### Clock frequencies

Per-target Scala `Main` objects have their own defaults: **TT ASIC = 4 MHz**, **ULX3S = 25 MHz (SoC) / 125 MHz (HDMI)**. Override via the `CLOCK_MHZ` env var (e.g. `CLOCK_MHZ=50 make generate_verilog`).

### Host Vulkan driver — `borgvk` (work in progress)

A real Mesa Vulkan driver (native ICD, modeled on v3dv) that runs the **unmodified** Khronos `Vulkan-Tools/cube.c` on a Linux host and renders it on the ULX3S over the existing serial-over-USB link (`/dev/ttyUSB0` @115200). Two new git submodules at the repo root, same pattern as `tt`/`PeakRDL-chisel`:

- `Vulkan-Tools/` — upstream KhronosGroup, pinned; source of `cube/cube.c` (kept unmodified).
- `mesa/` — the `gonsolo/mesa` fork, branch `borg`; the driver lives in-tree under `src/borg/vulkan/` (added post-restart once the toolchain is in `flake.nix`).

The driver intercepts `vkQueueSubmit` (via Mesa runtime's `vk_queue.driver_submit`), reads the per-frame MVP from the bound uniform buffer, and ships it over serial to the `borg_kernel.c` firmware (wire protocol: 0xAD MVP, 0xAE geometry, 0xAF texture rows, 0xB0 borgc shaders). The kernel renders the frame via the autonomous TBR sequencer. No NIR→Borg compiler is needed for the cube demo (borgvk ships borgc-compiled shaders from Mesa's `src/borg/compiler/`). Full plan: `~/.claude/plans/atomic-questing-stream.md`. `flake.nix` carries the Mesa/Vulkan build deps (meson, ninja, vulkan-loader/headers, libdrm, spirv-tools, x11/xcb).

## Conventions to know

- Don't recreate the deleted TinyQV CPU or its nibble-serial QSPI protocol — Hutt's `Decoupled` buses are the current contract.
- Always create new commits; don't amend or force-push without explicit user OK.
- The top Makefile's `HAND_CHISEL` `find` paths list `fpga/ulx3s/soc/src` and `asic/tt/src`.
