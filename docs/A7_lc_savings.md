# A7: FPGA LC Savings Analysis

Last updated: 2026-04-12 (after Step 16.3)

## Current Utilization (post-nextpnr)

| Resource | Used | Available | % |
| --- | --- | --- | --- |
| **ICESTORM_LC** | **5268** | **5280** | **99%** |
| ICESTORM_RAM | 10 | 30 | 33% |
| ICESTORM_SPRAM | 0 | 4 | 0% |
| ICESTORM_DSP | 1 | 8 | 12% |
| SB_GB | 8 | 8 | 100% |

**Headroom: 12 LCs.** Every new feature must be paired with a savings.

## Per-Module LC Breakdown

From hierarchical `yosys stat` (pre-techmap generic gate counts). Memory
modules map to BRAMs and cost ~0 LUTs in synthesis. The remaining modules
account for the actual ~5268 LCs.

| Module | Generic Cells | Notes | Est. LCs |
| --- | --- | ---: | --- |
| **MulAddRecFN** (FMA unit) | 945 | 3-cycle FMA pipeline | ~800 |
| **TinyQVDecode** | 852 | RV32IC decoder (combinational) | ~500 |
| **TinyQVRegisters** | 843 | CPU GPR file (nibble-serial) | ~450 |
| **CsrFile** | 530 | Machine-mode CSRs | ~350 |
| **Fp16Rcp** | 598 | Reciprocal (BRAM LUT + interp) | ~120 |
| **BorgRasterizer** | 428 | Pixel FSM + snooping | ~350 |
| **QspiController** | 410 | SPI flash controller | ~300 |
| **TinyQVMemCtrl** | 373 | Memory arbiter | ~250 |
| **BorgGpuRegs** (RDL) | 292 | SystemRDL register block | ~200 |
| **BorgCore** (local) | 1192 | FPU control, MMIO decode, write-back | ~500 |
| **Borg** (local) | 473 | Top-level wiring + cmd FIFO | ~300 |
| **UART** (rx+tx) | 417 | Serial port | ~250 |
| **TinyQVShifter** | 149 | Barrel shifter | ~100 |
| **TinyQVAlu** | 81 | Integer ALU | ~60 |
| **Misc** | — | Counters, timers, glue | ~200 |

> **Note:** The pre-techmap→post-synth mapping is not 1:1 (ABC9 optimizes
> across boundaries), but relative sizes are reliable for identifying savings
> targets.

To regenerate this table, run the following in `fpga/`:

```bash
yosys -p "read -sv $(SRC_FILES); hierarchy -top tinyQV_top; \
  proc; opt; memory; opt; techmap; opt; stat -top tinyQV_top" \
  -DICE40 -DPURE_RTL -DSYNTH_FPGA -DENABLE_INITIAL_MEM_
```

## Savings Opportunities

### Tier 1: Already Planned (Step 20.0) — ~40 LUTs

| ID | Change | Savings | Notes |
| --- | --- | --- | --- |
| 20.0a | Remove IMEM MMIO write path | ~15 | DMA replaces `borg_write_imem()` |
| 20.0b | Remove uniform MMIO write path | ~15 | DMA replaces `borg_write_uniform()` |
| 20.0c | Simplify RDL address decode | ~10 | Fewer comparators after 20.0a/b |

### Tier 2: Low-Risk Structural Changes — ~80–130 LUTs

| ID | Module | Change | Savings | Risk |
| --- | --- | --- | --- | --- |
| ~~S1~~ | ~~**TinyQVRegisters**~~ | ~~Reduce CPU GPR 16→12~~ | ~~~60~~ | ❌ **Invalid** |
| S2 | **CsrFile** | Prune unused CSRs | ~40–60 | Low |
| S3 | **BorgCore** | Remove MMIO GPR read path | ~20–30 | Low |
| S4 | **BorgGpuRegs** | Remove shadow registers for read-only fields | ~15–20 | None |

**~~S1~~: CPU Register Reduction — INVALID with GCC.** r12–r15 are ABI
registers `a2`–`a5` (function arguments 3–6). GCC for `rv32e` uses them
freely whenever a function has more than 2 arguments or needs local
variables — regardless of what the current shader assembly happens to do.
Reducing `numRegs` to 12 would cause silent register corruption in any
GCC-compiled firmware. Only viable if all firmware is hand-written assembly
**and** the linker script explicitly forbids r12–r15 (non-standard). Do not
pursue without first switching to asm-only firmware.

**S2: CSR Pruning.** Full machine-mode CsrFile (530 cells) implements
`mcycle`, `minstret`, `mcycleh`, `minstreth`, `mtimecmp`, `mtimecmph`,
`mstatus.mte`, `mie`, `mscratch`, etc. For Phase 2 (no interrupts, no Linux),
only `mtvec`, `mepc`, `mcause`, `mscratch`, `mstatus.mie/mpie` are needed.
Parameterize the rest behind a `hasFullCSR: Boolean` flag.

**S3: GPR Read Path Removal.** The MMIO GPR read path (`regFileC` shared read
port for `core.io.regReadData`) is used only for CPU debugging. Once DMA is in
place, shader state inspection goes through DMA. Remove the `mmio_en` mux from
`wirePortC()`.

**S4: RDL Shadow Register Removal.** PeakRDL generates shadow flip-flops for
fields that are hardware-writable but also MMIO-readable. Several fields
(e.g., `iter_x`, `iter_y`, `iter_valid`) are read-only from the hardware
perspective — the RDL code generates redundant FFs. Adding `hwReadOnly` flags
to the RDL source removes them.

### Tier 3: Architectural Restructuring — ~150–250 LUTs

| ID | Module | Change | Savings | Risk | BRAM Cost |
| --- | --- | --- | --- | --- | --- |
| A1 | **TinyQVDecode** | Compressed decode → BRAM LUT | ~100–150 | Medium | +1 BRAM |
| A2 | **QspiController** | Share flash+PSRAM controller | ~80–120 | High | 0 |
| A3 | **UART** | Make UART optional | ~250 | Medium | 0 |
| A4 | **TinyQVShifter** | Nibble-serial shifter | ~50–80 | Low | 0 |

**A1: Decode BRAM.** The 852-cell TinyQVDecode is a combinational mux tree for
RV32IC. The compressed instruction decoder (14 `is()` cases, each computing
imm/rs1/rs2/rd/alu_op/mem_op) is essentially a lookup table. Encoding the 14
compressed formats into a BRAM lookup (32-bit entries, 256 entries indexed by
`{funct3, opcode[1:0], key_bits}`) would replace ~400 LUT4s with 1 BRAM
read + minimal glue. The 32-bit instruction path stays combinational.

**A3: Optional UART.** The UART consumes ~250 LCs. Making it a parameter
(`hasUart: Boolean`) frees 250 LCs for resource-constrained builds. FPGA
builds needing serial debug keep it enabled.

**A4: Nibble-serial shifter.** TinyQV already uses nibble-serial processing
for counters and timers. The barrel shifter (149 cells) could be replaced with
a 4-bit-per-cycle iterative version, saving ~50–80 LUTs at the cost of 8
extra cycles per shift. Shifts are rare in GPU firmware.

### Tier 4: SPRAM Migration — enables future features

| ID | What | From | To | SPRAM Cost | Why |
| --- | --- | --- | --- | --- | --- |
| P1 | Texture cache (Step 21) | LUTs | SPRAM | 1 | Full-coverage 12-bit cache |
| P2 | DMA staging buffer | LUTs | SPRAM | 0 (shares P1) | Sequential access OK |
| P3 | Tile buffer (future) | 1 BRAM | SPRAM | 1 | Frees 1 BRAM |

**P1: SPRAM Texture Cache.** Instead of the 4-line FF-based cache (Step 21,
~30 LUTs), use 1 SPRAM as a 16K×16 cache. This gives 4096 unique texel
entries (12-bit Morton address space) — 100% hit rate for 64×64 textures. No
tag comparison needed (direct-mapped, full-coverage). Cost: 0 LUTs + 1 SPRAM.
SPRAM is single-port, but `sTexFetch` already serializes access.

## Summary

| Tier | Savings | BRAM | SPRAM | Risk | When |
| --- | --- | --- | --- | --- | --- |
| 1: Planned (20.0) | ~40 | 0 | 0 | None | Step 20 |
| 2: Structural | ~80–130 | 0 | 0 | Low | Step 17–18 |
| 3: Architectural | ~150–250 | +1 | 0 | Medium | Step 17 |
| 4: SPRAM migration | 0 (enables) | −1 | +2 | Low | Step 21 |
| **Total** | **~270–420** | **+1** | **+2** | — | — |

With Tier 1+2 alone (~120–170 LUTs freed), the design drops to ~5100 LCs,
giving comfortable headroom for Steps 18–21.

With Tier 1+2+3 (~270–420 LUTs freed), the design drops to ~4850–5000 LCs,
leaving room for the full Phase 2 feature set.

## Recommended Priority Order

1. **S4** (RDL shadow registers) — zero risk, ~15 LUTs, immediate
2. **S2** (CSR pruning) — low risk, ~40–60 LUTs, parameterize now
3. ~~**S1** (CPU GPR reduction)~~ — ❌ invalid with GCC (`a2`–`a5` are r12–r15)
4. **20.0a/b/c** (planned DMA prerequisites) — at Step 20
5. **A3** (optional UART) — medium risk, ~250 LUTs, parameterize now
6. **A4** (nibble-serial shifter) — low risk, ~50 LUTs, uses existing pattern
7. **A1** (decode BRAM) — medium risk, ~100 LUTs, needs careful testing
8. **P1** (SPRAM cache) — no LUT savings but better cache, at Step 21
