# A6: Resource Audit (STALE — originally iCE40 UP5K; current FPGA target is ECP5-85K/ULX3S)

Last updated: 2026-04-07 (after Step 12 optimization)

⚠ This document describes the iCE40 UP5K (pico-ice) which is no longer a supported target. The active FPGA target is ULX3S (Lattice ECP5-85K) with ~84k LUTs. Numbers below are iCE40 UP5K only.

## Current State (iCE40 UP5K — HISTORICAL)

| Resource | Used | Available | Free | Unit Size |
| --- | --- | --- | --- | --- |
| **LCs (packed)** | **5204** | **5280** | **76** | 1 LUT4 + 1 DFF |
| BRAM (EBR) | 10 | 30 | 20 | 4 Kbit, dual-port, initializable |
| SPRAM | 0 | 4 | 4 | 256 Kbit, single-port, no init |
| DSP | 1 | 8 | 7 | 16×16 multiply |
| LUT4 (yosys) | ~4020 | 5280 | ~1260 | — |
| DFF (yosys) | ~1500 | 5280 | ~3780 | — |

## BRAM vs SPRAM

| | BRAM (EBR) | SPRAM |
| --- | --- | --- |
| Full name | Embedded Block RAM | Single-Port RAM |
| Count | 30 | 4 |
| Size each | 4 Kbit (256×16 or 512×8) | 256 Kbit (16K×16) |
| Total | 120 Kbit (15 KB) | 1024 Kbit (128 KB) |
| Ports | Dual (1R + 1W simultaneous) | Single (read OR write per cycle) |
| Init values | Yes (preloaded at config time) | No (undefined at power-on) |
| Latency | 1 cycle | 1 cycle |
| Chisel | `SyncReadMem` → Yosys infers automatically | Needs explicit `SB_SPRAM256KA` blackbox (iCE40 only; not valid on ECP5 or IHP ASIC) |
| Best for | Register files, FIFOs, small ROMs | Runtime caches, DMA buffers |

## Current BRAM Allocation (10/30 used)

| Structure | Size | EBRs | Module |
| --- | --- | --- | --- |
| Register file A/B/C | 3 × 30×16 | 3 | BorgCore |
| Instruction memory | 64×32 | 1 | BorgCore |
| Uniform buffer | 32×16 | 1 | BorgCore |
| Tile buffer RGBZ | 16×64 | 4 | BorgTileBuffer |
| **Spare** | — | **20** | — |

## Phase 2 Resource Projection (Steps 12–17)

| Step | LUTs | DFFs | BRAM | SPRAM | Strategy |
| --- | --- | --- | --- | --- | --- |
| 12: Z-Buffer ✅ | +40 | +0 | — | — | BRAM-based read-modify-write |
| 13: Command FIFO | +90 | +25 | +2 | — | FIFO entries in BRAM |
| 14: Texture Fetch | +100 | +30 | — | +1 | Texel cache in SPRAM |
| 15: DMA Engine | +90 | +65 | — | +1 | Staging buffer in SPRAM |
| 15.5: TileLink | +10 | +10 | — | — | Roughly neutral |
| 16: Vertex Sequencer | +80 | +30 | — | — | Reuses DMA engine |
| 17: Integration | +10 | +5 | — | — | Wiring only |
| **Phase 2 Total** | **~4400** | — | **12/30** | **2/4** | **83% LCs** ✓ |

## Phase 3 Projection (Steps 18–22)

| Step | LUTs | Notes |
| --- | --- | --- |
| 18: M Extension | +100-200 | Use DSP for multiply |
| 19: A Extension | +100 | LR.W/SC.W |
| 21: MMU (Sv32) | +800-1200 | ⚠ Likely exceeds UP5K |

Phase 3 with MMU pushes to ~5400 LUTs ≈ 102% of the iCE40 UP5K ceiling. No-MMU Linux (Step 20)
fits; full MMU likely requires the ASIC tapeout. (Note: actual ASIC submission was 4×2 tiles on IHP SG13G2, not a 4×5 tile target.)

## Phase 5 (ASIC only)

| Step | LUTs | BRAM | SPRAM | DSP | Notes |
| --- | --- | --- | --- | --- | --- |
| 28: Integer ALU | +50-100 | — | — | — | Comparison, bitwise |
| 29: Memory Load/Store | +100-150 | — | +1 | — | Shader DRAM access |
| 30: Framebuffer Blend | +50-80 | — | — | — | Alpha blend unit |
| 31: Multi-Lane SIMD | +200-400 | +4-8 | — | +2-4 | 2-4 parallel FMAs |

Phase 5 is explicitly "only makes sense on a larger tile or ASIC."

## Design Rules

| Rule | Rationale |
| --- | --- |
| No `VecInit` ROMs > 16 entries | Use BRAM instead; saves ~4 LUTs per entry |
| Store FIFOs/buffers in BRAM | 20 EBRs free; each saves 64–256 FFs |
| Use SPRAM for runtime caches | 128 KB free; texture/DMA natural fit |
| Use DSP for multiplies | 7 DSPs free; each saves ~100 LUTs |
| Monitor packed LCs after every step | `cd fpga/ulx3s && make` (ECP5 target; iCE40 `borg.asc` no longer exists) |
