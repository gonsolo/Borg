# borgc: legalization and a spilling allocator

Status: steps 1 to 5 built 2026-10-28, CTS on both corpora (6,276 cases): no Pass lost, 6 fixed.
Built: legalization; late spilling in compute and, as the ladder's last try, in fragment shaders;
SWS. Replay: 1,330 -> 1,065 compiles; 4 compute shaders shrink (3,346 -> 2,811 words); 1 refused
compute and the 2 refused fragment shaders compile. Step 6, one compile with late spilling, measured
and refused (below); the ladders go with C instead. Step 5 of B10, items 1 and 2 of the
borgc architecture list. B10's logical CFG (`block::Cfg`, dominators) is built for it.

## Today

Measured on the replay (797 shaders, `out/borgc_replay`, `BORGC_DEBUG=1`):

- 1,330 compiles for 797 shaders, 537 of them ending "out of registers". Graphics retries up to
  28 times (7 spill limits, 2 constant gaps, 2 schedules), compute up to 80 (`lib.rs`,
  `compute.rs` `compile_unified`); each retry selects again from NIR.
- Graphics never spills: `spill_one` refuses a shader with a branch, a derivative or an
  implicit-LOD TEX, because a helper lane skips its STOREs (`BorgCore` `laneHelper`) and would
  reload a wrong value. All 216 spills are compute.
- Every failed allocation (536) is pressure: more values live at one point than free registers
  (the 2 refused fragment shaders: 85 live, 26 free). The regalloc debug line's "peak pressure"
  counts only the values that got a register. The allocator is not the problem; spilling is.
- Legalization is spread over `split_window_reads`, `v2_operands`, `place_const_operands`,
  `copy_tex_results` and `window_copy`; the encoder counts what it cannot encode (`pending`,
  `bad`) afterwards.

## Design

| Piece | What |
|---|---|
| Legal | `legal.rs`: what the encoder takes with the allocated registers. No operand without a register, at most one window operand, none in an immediate or RISC-V form, x0 not as base, store value or destination. `check()` runs before encoding; a refusal names the instruction. |
| Legalize | one pass, `legalize.rs`, between the pressure and the latency schedule: the fix-ups above move into it. ISA forms (`iseq_forms`, `fstep_forms`) stay lowering, not legalization. |
| Spilling | `spill_to_fit`, after all passes, on the allocator's pressure: while more values are live than registers, spill the one with the furthest next use. Each definition (a phi web's every copy) gets its own register and a store; each use a reload. Constants are never chosen. |
| Helper lanes | they also execute spill stores (hardware, below), so every stage can spill. |
| Compile | one selection; the recompile ladders go (step 6; today late spilling is the ladder's last try). |

Spilling works on today's program, where B10 step 3 already made each phi web one register.
Braun-Hack spilling in SSA would move out-of-SSA after spilling; only if reload cost shows up.

### Hardware: spill stores in helper lanes

Vulkan forbids a helper invocation's stores to have an effect; a spill slot is private to the
lane, so its store has none. SWS marks it: an S-format store in custom-2 (funct3 2) that the core
does not mask by `laneHelper` (one decode bit, no address compare), in `hardware/borg/isa/rv_i`.

## Steps

5a keeps the replay byte-identical; 5b changes code, checked by the replay statistics (bytes,
spills, compiles, compile time), both corpora as a CTS pack, and the vertex and fragment CTS.

1. 5a: `legal()` and the check before encoding.
2. 5a: each fix-up into `legalize.rs`, one per commit.
3. 5b: late spilling in compute. Reloads per block were tried and dropped: no gain.
4. Hardware: the spill store helper lanes run, its encoding, a Chisel test with a helper lane.
5. 5b: fragment shaders spill with it; `needs_helper_lanes` goes; the 2 refused shaders compile. A
   vertex shader that branches still does not spill.
6. 5b: the recompile ladders go. Measured as one compile with late spilling and refused; now C.

## Step 6, measured

One compile with late spilling (mesa `feat/borgc-one-compile`, not merged), as a cycles pack
against main on the 6,276 cases (`chunk_cycles.txt`): +19.9% cycles over the 117 chunks both ran
cleanly; 43 Pass -> Fail (38 depth line lists, whose vertex shaders could not spill; 3 precision; 2
compute shared atomics), 6 -> Timeout. `roundeven.highp.vec4`: main compiles 5 times and keeps 4
constants in registers, no spill, 9.19 M cycles; one compile spills 8 values, 15.46 M.

The ladder's constant cap is rematerialization by hand. The late spiller does the reverse: values
go to memory, constants keep their registers. Every Braun-Hack spiller in Mesa (NAK, ir3, ACO,
Bifrost) evicts a rebuildable value first, and LLVM halves its spill weight.

## C: a Braun-Hack spiller

Braun & Hack, "Register Spilling and Live-Range Splitting for SSA-Form Programs" (CC 2009), as NAK
(`spill_values.rs`) and ir3 (`ir3_spill.c`) build it.

| Piece | What |
|---|---|
| Next use | per block on `block::Cfg`: each value's distance to its next use; an edge leaving a loop counts as far, so uses after the loop do not keep a value in a register through it. |
| In registers | per block entry: a loop head takes what the loop uses; a join what its logical predecessors all have, as a reload under a mask reaches that edge's lanes only. |
| Eviction | rebuildable values first (a constant: its reload is LUI/ADDI), then the furthest next use. |
| Stores | once after each definition (each definition of a phi web): SW in compute, SWS in draw shaders. None for a constant. |
| Reloads | at a block's entry or before the use, into a fresh value; reloads on every edge into a join define the same value, as a phi web. |
| Pressure | counted on the linear CFG, as `live_ranges`; the TEX block counts 4 while live. |
| Barriers | no value in a register across a BARRIER: the set empties there. |
| Constants | values without a fixed register; `pool_consts`'s cap and `const_gap` go, the spiller decides. |
| Allocation | once, with a margin of 1-2 registers for the fixed constraints (TEX block, FATTR, window). |

Steps, each with the tests and the replay statistics; cycles packs against main at C3 and C5:

1. C1: next-use distances and loop structure on `block::Cfg`, with unit tests.
2. C2: the spiller for values, as the ladder's last try instead of the late spill; same slots and
   lane keys, vertex shaders included.
3. C3: constants rebuilt by the spiller instead of held in fixed registers.
4. C4: barriers; `spill_across_barriers` goes.
5. C5: one compile; the ladders, the early spill and `spill.limit` go. Both corpora and a vertex
   pack (glsl matrix, loops, indexing, branches).

## Risks

- Reloads under masks: a reload runs for its block's lanes only, so each sits right before its use.
- Compile time: one compile with a spill loop must cost less than today's ladders; measured on
  the replay.
- C: masked edges, unit-tested per shape (if/else, nested if, loop with an early exit, a value
  carried round a loop); the 2 compute shared atomics step 6 broke, checked again at C5.
- Spill memory per lane: graphics 16 slots for early spills (`MAX_SPILL_SLOTS`), 128 for late
  ones (`MAX_LATE_SLOTS`); compute 64.
