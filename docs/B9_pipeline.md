# Pipeline

Status: step 1 built and on main (2026-10-27), on every target; step 2 (GCN-style, 2026-10-29) is the plan. It builds on
ISA v2 (B8, on main since 2026-10-25). A pipeline that writes several instructions' results in
flight cannot also watch register numbers: B8 removed every such role except the dispatcher's
snoop of the raster ROM's registers, which went first (Order, step 0, 2026-10-25).

## Before the pipeline

One instruction at a time per quad, no overlap:

| Cycle (busy counter) | What happens |
|---|---|
| decode | read rs1 (the register file has one read port per lane) |
| 7 | read rs2 |
| 6 | read rs3 |
| 5 | rs3 latched |
| 4 | operands valid: FMA stage 1, integer ALU, branch, exec mask, memory and TEX start |
| 3 | FMA stage 2 |
| 1 | write-back |

8 cycles per instruction on Default and ULX3S (`fmaStages = 3`), 9 on Wafer (`fmaStages = 4`),
whatever the instruction: an `ADD` waits for the FMA's latency. LOAD, STORE, TEX, ZTEST, SOUT
and FATTR stop the counter and run a state machine that serves the four lanes in turn; each adds
a cycle after it resumes. The pipeline is idle about 7 cycles in 8.

ULX3S (2026-10-07, Linux + Borg `Tiny` + HDMI): 71,312 of 83,640 LUTs, 14,414 FFs, 23 of 208
DP16KD, 75 of 156 MULT18X18D, 321 LUTRAM blocks (TRELLIS_DPR16X4). The core clock reaches
19.01 MHz against 18.75 MHz; the critical path ends in a lane's special-function unit
(`lanes_3.special_result`, its correction multiplier). On Wafer the FMA is the limiter, hence
`fmaStages = 4` at 8 MHz.

ULX3S vkcube SoC after B8 (2026-10-25, nextpnr): 72,607 LUTs (86%) against 62,695 on main
before it, 12,269 FFs, 68 MULT18X18D (56 before), 34 DP16KD; the core clock 18.30 MHz against
16.93 (target 25). ISA v2 costs vkcube 15.7% more cycles per frame (2.56 M to 2.96 M): constants
by LUI and ADDI, uniforms by a CSR read, gl_Position by CSR writes. The pipeline is where that
comes back.

## Step 1: an in-order pipeline with interlocks, one quad

### Stages

| Stage | Work |
|---|---|
| F | instruction fetch from IMEM / the I-cache; PC + 4 |
| D | decode; read rs1, rs2, rs3 (three read ports); scoreboard check; the quad's exec mask taken with the instruction |
| X1..Xn | integer ALU, compares, Zbb, Zicond (n = 1); FMA, conversions (n = `fmaStages`); special functions (several cycles); memory, TEX, atomics (variable) |
| W | write-back, gated by the instruction's lane mask |

Lanes stay in lockstep: all four go through each stage together.

### Hazards and how each is handled

| Hazard | Handling in step 1 |
|---|---|
| Read after write | A scoreboard bit per register, set when an instruction that writes rd issues, cleared at its write-back. D stalls while a source register's bit is set. No forwarding. |
| Write after write | The same scoreboard on rd: a later write cannot issue while an earlier one to the same register is in flight, so a short operation cannot overtake a long one. |
| Write port | One write port per lane. Every arithmetic result takes `fmaStages` cycles: the integer path is padded to the FMA's length, so two results never meet at W. Variable-latency units (special functions, memory, TEX) take W in a slot the issue logic reserves. |
| Branches | `BEQ`..`BGEU` and `JAL` resolve in X1 on the lowest active lane (as v1); F stops after a branch until it resolves, so nothing is flushed. Cost: 2 cycles per taken or untaken branch. |
| Exec mask | `EXPUSH`, `EXELSE`, `EXPOP` update the mask in X1; D holds the next instruction until then (one bubble). Each instruction carries the mask it issued with to W, so a masked lane never writes. |
| `EXANY`, `DDX`, `DDY` (and `QBALLOT`, `QSHUF` with Vulkan 1.1) | read all lanes' operand in D (as the DDX path does today); no new hazard |
| Memory (`LW`, `SW`, AMOs) | The memory unit serves the lanes in turn as v1; D stalls until it is done (blocking). Loads write back through a reserved slot. Program order holds without further work. |
| TEX | Blocking, as v1: D stalls until the texture unit has written all four lanes. |
| CSR reads | System values are constant per quad, `uniform0..31` constant per draw: no hazard. CSR writes (fragment outputs, `out_pos`, the setup ROM's `setup_screen`/`setup_det` the walker captures) happen at W in order. |
| `BARRIER`, `ZTEST`, the end of the program | D waits until the pipeline is empty. |
| I-cache miss | F stalls. |

### Register file read ports

Three operands per cycle per lane (`FMADD`, `TEX`, `SOUT` with an index need three; most
instructions two).

- **ULX3S**: three copies of the LUTRAM register file, written together. Today a lane's file is
  one copy of 32 × 32 bits in TRELLIS_DPR16X4 (16 blocks); two more copies per lane are 128
  more blocks for the quad, of 321 in use.
- **Wafer**: a register file in flops costs about 124,000 µm² (`mem_32x32`) and most of it is
  the read multiplexer, so a second or third port is not affordable. Wafer keeps one read port
  and reads operands in turn, but overlaps the reads of the next instruction with the execution
  of the current one (see "Wafer").

### What it gains

The issue rate is one instruction per cycle when independent instructions follow each other.
Without forwarding, an instruction that needs the result of the one before waits `fmaStages + 1`
cycles (4 on ULX3S). borgc's scheduler already orders for register pressure; it gets a second
goal, distance between a value and its first use. Expected, to be measured on the corpus: about
2.5 to 3.5 cycles per instruction on typical shaders, against 8 today.

Forwarding (results from X1 or Xn into D's operands) and early write-back of short operations
come only if the stall counters show that dependent instructions dominate. They cost a
multiplexer per operand per lane and timing on the operand path.

### As built

`BorgCore.scala`, `BorgLane.scala`. An instruction issues at decode, reading rs1, rs2 and rs3
from the lane's three register-file copies, then moves through stages 5 to 1: operands held at 5,
valid at 4, the FMA until 2, write-back at 1. Each stage carries its instruction's operands and
flags (`BorgLane.later`); decode of the next instruction happens during write-back. The next
instruction issues every cycle unless:

- it reads a register an instruction in flight writes: it waits until the writer is in
  write-back, and the lane's write-through bypass hands it that word (a dependent instruction
  issues 5 cycles after its producer, 6 without the bypass);
- it follows a branch;
- it, or the instruction before it, runs a unit's state machine (memory, TEX, FATTR, SOUT) or is
  `HALT` or `BARRIER`: the stages drain first. The memory state machine holds the pipeline from
  its first cycle.

Immediates are no hazard. Unlike the plan, there is no scoreboard: the register check compares
against the stages in flight. Wafer runs the same pipeline (`231d3c58`); its three read ports
replace the overlapped reads planned below, and its area and clock with them are not measured yet.

Measured (direct_sim, 128 × 128, cycles per frame):

| Change | vkcube | stencilcube |
|---|---|---|
| Before the pipeline (after Order step 0) | 3,106,510 | 6,465,714 |
| Step 1: issue every cycle, three read ports, stage valid bits | 1,957,408 | 4,111,116 |
| Write-through bypass | 1,804,370 | 3,798,098 |
| borgc schedules for the pipeline's latency | 1,655,427 | 3,494,516 |
| Sampler: skip LOD for one level, ack on latch, skip zero multiplies, fold the axis state | 1,499,725 | |
| borgc refines 1/x, inversesqrt and division in every stage (precision) | 1,580,095 | |

vkcube's fragment shader after the bypass: 1.53 M cycles, of them 0.29 M issuing (5.3 cycles per
instruction), 0.60 M waiting on a register, 0.62 M draining. Of the drain, 88% is a unit holding
the pipeline: TEX 59%, memory 21%, FATTR 11%. TEX takes 263 cycles per quad with only about 32 of
them memory: the sampler's state machine, not memory, is the limit, so a texel cache buys 1-3%
with NEAREST.

ULX3S (vkcube SoC): step 1 costs +1,174 yosys LUT4 against the +800 to +1,500 estimated, most of
the growth flops (+511 per lane); nextpnr 73,489 LUTs (87%) at 19.70 MHz. With the bypass 73,243
LUTs at 19.57 MHz; the critical path starts at the uniform RAM.

## Step 2: several quads in flight

With one quad, the interlocks waste every cycle a dependent instruction or a TEX waits. A second
source of independent instructions hides that: several quads share the pipeline, and each cycle
D issues from another quad (fine-grained multithreading, as every GPU). The design follows AMD
GCN rather than a scoreboard machine: a fixed rotation over the quads hides the pipeline's
depth, and the compiler, not the hardware, tracks the long-latency units.

| Item | Design |
|---|---|
| Slots | `quadSlots` hardware quads: 4 on ULX3S, 1 on Wafer (see below), 8 later if it pays |
| Per slot | PC, exec-mask stack, CSR state (system values, outputs), a slot in the register file, two wait counters |
| Issue | round robin: the cycle after slot k issues, slot k + 1 is asked first. A slot without a quad, or waiting on a counter, a drain or `BARRIER`, gives its turn to the next. |
| Register hazards | The step-1 check against the stages in flight stays, compared within a slot. With four slots in rotation an instruction's successor in the same quad comes 4 cycles later, the bypass needs 5: a dependent pair waits one cycle, and only when the other slots are idle does it wait the full 5. No scoreboard. |
| Register file | block RAM: 4 lanes × 32 registers × 32 bits per quad = 4 Kbit. A DP16KD in 512 × 36 mode holds 16 quads' worth of one lane and one read port; three read ports and four lanes take 12 blocks for up to 16 quads (`23 of 208` in use today). The LUTRAM copies of step 1 go. |
| TEX, memory, FATTR, SOUT | non-blocking for the pipeline. Issue increments the slot's counter (one for TEX, one for memory and attribute traffic); the unit's result is written to the slot's registers when it arrives, through the write port in a cycle W leaves free, and decrements the counter. |
| Waits | `WAITCNT tex, mem` (new, custom-2) holds the slot until its counters are at most the given values, as GCN's `s_waitcnt`. borgc places it before the first read of a result, after the independent work it can schedule between. Before borgc places them, a slot waits right after each request (the first build), which is correct and still lets the other slots run. |
| Fragment order | Two quads covering the same pixels must write the tile in primitive order (blending, depth). The dispatcher does not start a quad whose 2 × 2 position matches a quad in flight from an earlier primitive. |
| Compute | quads of one workgroup run interleaved; `BARRIER` waits until every quad of the group arrives (the compute sequencer's segments become a counter) |
| Vertex | invocations are independent |

Expected: close to one instruction per cycle when four quads have work, TEX and memory latency
hidden behind the other quads instead of stalled. One quad alone runs as fast as in step 1: a
slot without a quad gives its turn away.

**Wafer.** Its register files are flops, 124,000 µm² per quad (see below), so four slots are
not affordable; `quadSlots = 1` is step 1 exactly. SRAM macros for the register files would
change that; to be measured before Wafer gets more slots.

## Wafer

Superseded: Wafer runs step 1 with three read ports (see "As built"). The plan was:

One quad, register files in flops, one read port. The step-1 pipeline with a single port: D
reads one operand per cycle into the operand latches while the previous instruction executes, so
a two-operand instruction issues every two cycles, a three-operand one every three, and a
dependent one after the FMA's 4 stages and write-back. Expected about 4 to 5 cycles per
instruction against 9 today, at the same clock: the FMA stays the critical path
(`fmaStages = 4`), and the added logic (scoreboard, padding, slot reservation) is small next to
it. Several quads in flight are not planned on Wafer: each quad needs its own 124,000 µm²
register files.

## Timing (ULX3S)

- D has the decoder, three LUTRAM reads and the scoreboard check: shallow logic, not expected
  to limit.
- The special-function unit limits today; `FEXP2` and `FLOG2` (B8) go into it. In the pipeline
  it gets its own stages (table read, interpolation multiply, correction), so its path is cut
  where it is longest now.
- The FMA keeps `fmaStages = 3`; the integer path padded to it adds registers, not logic.
- Target: at least today's 18.75 MHz, measured after each step; a step that loses clock gets
  its stages cut, not its function.

## Area (ULX3S LUTs, estimates)

| Piece | Estimate |
|---|---|
| Step 1: stage registers per lane, instruction and mask carried along | +300 to +600 |
| Step 1: two more LUTRAM register-file copies per lane | +250 to +500 (128 DPR16X4) |
| Step 1: scoreboard, issue, write-back slots, padding | +250 to +400 |
| **Step 1 total** | **+800 to +1,500** |
| Step 2: per-slot PC, exec stack, CSR state, wait counters (4 slots) | +500 to +800 |
| Step 2: round-robin issue, result write-back slots, fragment ordering check | +500 to +1,000 |
| Step 2: register files to block RAM | −250 to −500 LUTs (the step-1 copies), +12 DP16KD |
| **Step 2 total** | **+750 to +1,300**, 12 DP16KD |

B8 as measured (2026-10-25, vkcube SoC, yosys `synth_ecp5 -abc9` LUT4 cells, CCU2C apart):

| B8 step | LUT4 | MULT18X18D |
|---|---|---|
| RV32I OP, OP-IMM, LUI, AUIPC; loads, SW, CSRRS; branches, JAL | +1,197 | |
| Fragment outputs by CSR writes, no snooping | −573 | |
| Zmmul, Zicond, Zbb subset | +2,935 | +4 |
| Zfinx, Zhinxmin | +277 (+286 CCU2C) | |
| AMO*.W, AMOCAS.W | +838 | |
| Sampler descriptors by address | +390 | |
| FEXP2 and FLOG2 (`BorgExpLog`) | +3,063 | +5 |
| sRGB attachments in the tile path (`SrgbCodec`) | +4,136 | +3 |
| FSRGB dropped from the lanes | −2,614 | |
| v1 decode, TEX/TEXA, CSR and ROM moves, the rest | −847 | |
| **Total** (47,511 to 56,313) | **+8,802** | **+12** |

nextpnr counts 72,607 LUTs after B8, 11,033 free. The pipeline (+1,550 to +2,800) fits. The
largest B8 item to win back is the sRGB tile path: `SrgbCodec.encode` (two 256-entry tables, a
multiply, a 257-entry boundary table) sits at the tile buffer's clear register and write port and
at both flusher paths, three channels each, and a narrow tile's flush decodes its stored sRGB
bytes only to encode them again. A step over its budget gets its RTL redesigned; the four
fragment lanes stay.

## Measuring

The performance counters (`perf_total`, `perf_frag`, stall counters; see the reference note on
Borg perf counters) get, per quad: instructions issued, cycles stalled on a register, on the
write port, on memory, on TEX, on a branch. Built as `perf_issue` (the last word of the MMIO
map): write 0..6 to pick instructions issued, waits on a register, behind a branch, for a
drain, of those the waits for empty stages and while a unit holds them, and drain instructions
issued. Each step reports cycles per instruction on the
borgc corpus, the quick driver corpus and the cube (frames per second on the board), next to
LUTs, FFs, block RAM and Fmax.

## Order

0. The raster ROM's results by CSR writes, as the setup ROM's (`setup_screen`, `setup_det`):
   the dispatcher stops snooping its registers (`BorgRasterRom.planeRegs`: r1-r4, r10; the draw
   ROM's barycentrics r5-r8, sample depths r11-r14 and r29). B8's step 7c left this. The window's
   storage stays as the ROMs' `uniform0..31` CSRs. Done 2026-10-25: `rast_e0..2`, `rast_zn`,
   `rast_zf`, `rast_frag_z`, `rast_sample_z0..3` (0x818-0x823); the draw ROM grew from 51 to 61
   words (6 more per pixel at one sample, 4 more at MSAA): vkcube 2.96 M to 3.11 M cycles per
   frame (+5.0%) until CSR writes are short in the pipeline; ULX3S yosys +329 LUT4.
1. The step-1 pipeline. Done 2026-10-27, with the write-through bypass; since one-design on
   every target, without `BorgConfig.pipelined`.
2. borgc's scheduler gets the distance goal; measure. Done 2026-10-27: the latency scheduler
   (mesa `c92731f09c0`), vkcube −8.3%.
3. Wafer: overlapped operand reads. Superseded by three read ports on Wafer.
4. Next, against the drain share: branch redirect at X1; pipelined LOD multiplies in the sampler
   for mipmapped textures (72 cycles to about 30).
5. Step 2, in stages (vkcube before it, 2026-10-29: 1,648,670 cycles per frame, the shader 1.38 M
   of it: 0.29 M issuing, 0.60 M waiting on a register, 0.39 M on a unit; expected about 0.6 to
   0.75 M after, with the sampler's own busy time the new limit):
   a. The quad's state as a slot of the core, `quadSlots = 1`: PC, exec mask and stack, barrier
      state, the per-quad inputs latched at the trigger, the register file addressed by slot.
      Cycle-identical.
   b. Non-blocking TEX, memory, FATTR, SOUT and FEXP2/FLOG2: results written back when the
      pipeline's write port is free, the slot's counters, the implicit wait.
   c. The dispatcher keeps a quad per slot through its phases (edge test, destination read,
      shading, depth test and tile write), starts a quad into a free slot, holds back one that
      overlaps an earlier primitive's quad in flight, and takes each slot's outputs tagged.
      Vertex, setup and compute stay on slot 0.
   d. `quadSlots = 2`, then 4 on ULX3S: cycles, LUTs, block RAM, Fmax.
   e. `WAITCNT` and borgc placing it.
6. Forwarding, early write-back of short operations, non-blocking memory within a quad: each
   only if the counters show its stall dominates.

Each step: Chisel unit tests for the hazards it adds (one test per row of the hazard table),
direct_sim, the borgc corpus, the quick driver corpus, the cube, and the measurements above.
