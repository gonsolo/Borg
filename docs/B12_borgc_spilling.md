# borgc: legalization and a spilling allocator

Status: steps 1 to 5 built 2026-10-28, CTS pending: legalization; late spilling in compute and, as
the ladder's last try, in fragment shaders; SWS. Replay: 1,330 -> 1,065 compiles; 4 compute shaders
shrink (3,346 -> 2,811 words); 1 refused compute and the 2 refused fragment shaders compile. Step 6
open. Step 5 of B10, items 1 and 2 of the borgc architecture list. B10's logical CFG (`block::Cfg`,
dominators) is built for it.

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
| Legal | `legal(instr)`: what the encoder takes. Register-only RISC-V forms, at most one window operand, immediate ranges, the TEX result block r20-r23, consecutive FATTR results. Checked before encoding; a refusal names the instruction. |
| Legalize | one pass, `legalize.rs`, after scheduling: the fix-ups above move into it. ISA forms (`iseq_forms`, `fstep_forms`) stay lowering, not legalization. |
| Spilling | in one compile, on the selected program: while allocation does not fit, spill the value with the furthest next use. Stored once after each definition (a phi web's every copy), reloaded once per block where used. A constant is rebuilt, never stored. |
| Helper lanes | they also execute spill stores (hardware, below), so every stage can spill. |
| Compile | one selection; the recompile ladders go. |

Spilling works on today's program, where B10 step 3 already made each phi web one register.
Braun-Hack spilling in SSA would move out-of-SSA after spilling; only if reload cost shows up.

### Hardware: spill stores in helper lanes

Vulkan forbids a helper invocation's stores to have an effect; a spill slot is private to the
lane, so its store has none. A store form marks it: SW with a flag the core does not mask by
`laneHelper` (one decode bit, no address compare). Its encoding goes into the ISA opcode file
(`hardware/borg/isa/opcodes`) with the other custom forms.

## Steps

5a keeps the replay byte-identical; 5b changes code, checked by the replay statistics (bytes,
spills, compiles, compile time), both corpora as a CTS pack, and the vertex and fragment CTS.

1. 5a: `legal()` and the check before encoding.
2. 5a: each fix-up into `legalize.rs`, one per commit.
3. 5b: spilling in one compile; reloads per block; constants rebuilt.
4. Hardware: the spill store helper lanes run, its encoding, a Chisel test with a helper lane.
5. 5b: fragment and vertex shaders spill with it; `needs_helper_lanes` goes; the 2 refused
   shaders compile.
6. 5b: the recompile ladders go.

## Risks

- Reloads under masks: a reload in a block runs for that block's lanes only; a value reloaded in
  one side of an if is not in its register on the other. Reloads are per block for this reason.
- Compile time: one compile with a spill loop must cost less than today's ladders; measured on
  the replay.
- Spill memory: 16 graphics slots per lane today (`MAX_SPILL_SLOTS`), 64 in compute.
