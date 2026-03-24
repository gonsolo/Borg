# Appendix A4: Experiments

## Nibble-Serial FMA (2026-03-19 → 2026-03-23)

**Goal:** Save LUTs by replacing the combinational 11×11 HardFloat multiply
with a multi-cycle nibble-serial implementation (4 bits per cycle).

**Result:** Saved ~215 LUTs (4%) but added significant pipeline complexity.

| Metric | Combinational | Nibble-Serial |
|---|---|---|
| LUTs | ~4461 (85%) | ~4246 (80%) |
| Latency | 4 cycles/instr | ~16 cycles/instr |
| Pipeline control | Simple countdown | `fma_inflight` + ready handshake |
| Test reliability | Solid | Intermittent failures |

**Why removed:** The 4% LUT savings was not worth the ongoing complexity:

- Required `fma_inflight` flag to avoid race conditions between valid/ready
  signals — a race that didn't exist with the combinational approach.
- Made register file expansion harder: the longer execution window needed
  more careful multi-port timing.
- The combinational FMA is well-tested, predictable, and fits comfortably
  on the current iCE40 tile even with the 32-register expansion.
- The roadmap plans a larger tile for Phase 2+, where 215 LUTs is noise.

**Files removed:**
- `hardware/borg/src/MulAddRecFNNibbleSerial.scala` — nibble-serial multiplier
- `borg/test/src/NibbleSerialTests.scala` — dedicated test suite

**Lessons learned:**
1. Area micro-optimizations that add pipeline control complexity are fragile.
2. The `SyncReadMem` 3-read-port pattern in Chisel interacted badly with
   the nibble-serial timing, but was fine with the simpler 4-cycle pipeline.
3. For GPU shaders, throughput (vertices/sec) matters more than per-instruction
   latency. The 4× faster combinational FMA actually helps throughput.
