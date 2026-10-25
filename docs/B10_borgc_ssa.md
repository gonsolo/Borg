# borgc in SSA

Status: design for review, 2026-10-25, not implemented. Item 3 of the borgc architecture list
(basic blocks, SSA, use-def chains), after B8 and next to B9, whose scheduler wants blocks too.
The spilling allocator (item 2) comes after it, on top.

## Today

- NIR is SSA until the end of `borg_nir_passes.c`, which runs `nir_convert_from_ssa` ("phis
  become registers for every stage"). borgc's selection never sees a phi: a loop variable is a
  NIR register, written in several places.
- The program is one flat `Vec<BorgInstr>`. Control flow is markers in it: `Label`,
  `EXPUSH`/`EXELSE`/`EXPOP`, `EXANY` and `BRNZ` for loops, `LoopHead`/`LoopEnd` until selection
  lowers them.
- Passes walk the list and keep facts in maps that a later write of the same register makes
  stale (`pipeline.rs`, "Not every value is written once"). Each pass decides for itself where
  to forget them: `root_accesses` forgot `v = x + k` too late (fixed 10-25, mesa
  `feat/root-accesses-loop`).
- The allocator (`regalloc.rs`) computes live ranges on the list; `extend_for_loops` stretches
  a value live into or across a loop over the whole loop.
- Out of registers, the shader is compiled again with a tighter spill limit, up to 7 times for
  graphics and 80 for compute.

## Why the exec mask matters

Borg is SIMT with structured masking: both sides of an `if` run, each under its mask, and a
loop runs until `EXANY` sees no lane left. A masked lane keeps its register's old value. This is
ACO's situation, not NAK's (NVIDIA threads branch on their own):

- **Logical CFG**: the per-lane control flow, the one NIR has. SSA, phis and value facts live
  here. A phi's meaning is per lane: the value from the edge that lane took.
- **Linear CFG**: what the quad executes, every block in program order, loops by `BRNZ`.
  Register interference lives here: a register written for some lanes must stay intact while
  other lanes still need its old value. `extend_for_loops` is this rule for loops today.

## Design

| Piece | What |
|---|---|
| IR | `Program { blocks: Vec<Block> }`, a `Block` holds instructions, its logical and linear successors and predecessors, and its mask nesting. Instructions keep `BorgInstr`'s shape; a value is defined once. |
| Phis | `Mnem::Phi` with one source per logical predecessor, at the top of a block. |
| Entry | `nir_convert_from_ssa` goes; selection maps NIR blocks to `Block`s and NIR phis to `Phi`s (as NAK's `from_nir.rs`). |
| Passes | DCE, constant facts (`bits_of`), the address folds, `fuse_fmadd`, TEX address forming become per-value: a fact about a value holds wherever the value is used. Use-def chains replace rescans. |
| Scheduling | within a block, by pressure and (B9) by distance to the first use |
| Out of SSA | before allocation: each phi becomes parallel copies at the end of its predecessors, inside their mask region, so only the lanes that took that edge copy; copies of a cycle get a temporary (the swap problem). Values whose live ranges do not interfere on the linear CFG are coalesced into one register first, so most phis cost nothing. |
| Allocation | liveness on the linear CFG; `extend_for_loops` becomes the general rule rather than a special case. The spilling allocator (item 2) replaces the recompiles on top of this. |
| Emission | blocks in linear order; branches and labels from the linear successors. |

Constraints that stay explicit, for the legalization pass (item 1): the TEX result block
(r20-r23), consecutive FATTR results, register-only operands of RISC-V forms, the uniform CSR
reads.

## Steps

Each step keeps the replay (797 shaders, `out/borgc_replay`) byte-identical where the step does
not intend to change code, and runs both corpora where it does.

1. Blocks over today's list: `Program` built from the markers after selection, passes unchanged
   (they flatten and rebuild). Byte-identical.
2. Liveness and allocation on blocks, linear CFG explicit; `extend_for_loops` derived from it.
   Byte-identical.
3. SSA: keep NIR's phis (drop `nir_convert_from_ssa`), selection emits `Phi`, out of SSA before
   allocation. Byte-identical is not expected (copy placement changes); the corpora decide.
4. Passes per value, one per commit (`root_accesses`, constants, TEX addresses, `fuse_fmadd`,
   DCE), deleting their stale-fact handling.
5. Legalization pass (item 1), then the spilling allocator (item 2).

## Risks

- Copies under masks: a copy outside its predecessor's mask region would overwrite lanes that
  took another edge. Unit tests per shape: if/else, nested if, loop with early exit, loop with a
  value carried round it.
- Compile time: borgc compiles at submit time; the IR must not cost more than the recompiles it
  ends. Measured on the corpus.
- Compute shares the selector (`compute.rs`) and goes through the same steps.
