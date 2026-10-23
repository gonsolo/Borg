# ISA v2

Status: specification agreed 2026-10-22; in progress on `feat/isa-v2-step1` (steps 1-4, 7, 8, 10,
12 and the first half of 11 done; 7c moved to B9). The current ISA (v1) is
`hardware/borg/isa/opcodes`; the facts about it below come from the RTL as of `fe1c9897`. The
pipeline that follows it is B9.

## Goals

- **A RISC-V subset**: RV32I + Zfinx + Zmmul + parts of Zbb + Zicond, with standard encodings,
  plus GPU operations in the custom opcode spaces.
- **No fixed register roles.** Every register is a general register; values the hardware
  produces or consumes are read and written explicitly.
- **Descriptors as plain memory.** The shader passes descriptor addresses in registers; the
  `TEX_DESC_BASE`, `SAMPLER_DESC_BASE` and `LS_BASE` registers go.
- **Loads, stores and atomics by address in every stage**, with a memory model that carries
  Vulkan's, and later buffer device address and the Vulkan 1.3 memory model.
- **Subgroup operations** across the four lanes of a quad.
- **A base-and-limit check** per context as minimal memory protection.
- **One ISA, a configuration per target**: every instruction exists on every target; a target
  may run some of them in more cycles.

Not goals: traps, a stack, function calls through memory, privilege modes, virtual memory.

## Decisions

| Question | Decision | Why |
|---|---|---|
| How does a shader find its descriptors? | Through CSR `root`, the address of the draw's root table | A CSR keeps every register general; it costs one instruction per shader. AMD and Intel preload table pointers into registers by ABI, NVIDIA reads a constant bank. |
| `FDIV.S`, `FSQRT.S` | Not implemented | Vulkan asks for 2.5 ULP division and inherits `sqrt` from `inversesqrt`; `FRCP`/`FRSQ` with a Newton step meet that, as on NVIDIA, AMD and Mali. |
| `SB`, `SH` | Not in v2's first version; encodings reserved, the memory port prepared | Vulkan 1.0 storage is 32-bit; 8- and 16-bit storage are extensions. They need byte enables down to the memory (below). |
| `pow`, `exp`, `log` | `FEXP2` and `FLOG2` in the special-function unit; `sin`, `cos`, `tan` and the inverses as borgc polynomials on `FMA` | VideoCore IV's set (`RECIP`, `RECIPSQRT`, `EXP2`, `LOG2`); Mali also does the trigonometric functions in software |
| sRGB render targets | Fixed function: the tile loader decodes, the flusher encodes, the tile holds linear colour | What every GPU's ROP does; `FSRGB` and the shader-side decode table go |
| Migration | Gradual, one group of instructions or one register role per step, on main | v1 uses only major opcodes 0x00 and 0x04; every RISC-V major opcode ends in binary 11, so both decode side by side with no mode bit |

## What changes from v1

| v1 | v2 |
|---|---|
| Own formats: R4-type opcode 0x04, R-type opcode 0x00 + funct7 | RISC-V R/R4/I/S/B/U/J formats; GPU ops in custom-0..3 |
| One operand per instruction from a 32-word uniform window, picked by f3 | No window: immediates (`ADDI`, `LUI`) and loads from a constant buffer |
| No zero register; zero built as `r30 - r30` | `x0` is zero |
| `BRZ`/`BRNZ` on lane 0's raw bits, 10-bit offset; `JMP` 18-bit | `BEQ`..`BGEU` (12-bit), `JAL` (20-bit); the same uniformity rule |
| Comparisons `ISLT`, `ISLTU`, `ISEQ`, `FSTEP` | `SLT[U][I]`, `FEQ/FLT/FLE.S`, `MIN[U]`/`MAX[U]`, `FMIN/FMAX.S`, `CZERO.*` |
| `FNEG` + `FADD` for a subtraction | `FSUB.S`, `FSGNJ[N|X].S`, `FMSUB/FNMADD/FNMSUB.S` |
| `FSRGB`, idiom-matched from `pow` | gone; `FEXP2`, `FLOG2`; sRGB attachments in the tile path |
| r30/r31: pixel centre, VertexIndex/InstanceIndex, invocation IDs | CSR reads |
| r24-r29 snooped as fragment outputs, r19 sample mask, r25 kill | CSR writes |
| TEX writes r20-r23 by convention, FATTR r10-r12 | any aligned group the compiler picks (the hardware already allows it) |
| `TEX rd, u, v, ctl`: ctl = descriptor indices and options | `TEX rd, u, v, desc`: desc = texture descriptor address |
| `LOAD`/`STORE`: word index from `LS_BASE` | `LW`, `SW` at register + 12-bit offset |
| atomics as a per-lane critical section | `AMO*.W` (Zaamo) and `AMOCAS.W` (Zacas) |
| no protection | base and limit per context |

## Encoding

Standard RISC-V formats and opcodes, 32-bit little-endian words. The all-zero word stays the
end of a program: RISC-V defines it as illegal, so no valid program loses an encoding.

| Major opcode | Use |
|---|---|
| LOAD 0x03, STORE 0x23, OP-IMM 0x13, OP 0x33, LUI 0x37, AUIPC 0x17, BRANCH 0x63, JAL 0x6F, JALR 0x67, MISC-MEM 0x0F, SYSTEM 0x73 | RV32I, Zmmul, Zbb, Zicond, Zicsr (system-value CSRs), `FENCE` |
| OP-FP 0x53, MADD 0x43, MSUB 0x47, NMSUB 0x4B, NMADD 0x4F | Zfinx (FP32 in x registers), Zhinxmin conversions |
| AMO 0x2F | Zaamo, Zacas (32-bit) |
| custom-0 0x0B | Special functions and derivatives: `FRCP`, `FRSQ`, `FEXP2`, `FLOG2`, `DDX`, `DDY` |
| custom-1 0x2B | Texture and attachment: `TEX`, `TEXA`, `TEXS`, `TLD`, `ZTEST` |
| custom-2 0x5B | Geometry: `SOUT`, `FATTR` |
| custom-3 0x7B | Control and quad: `EXPUSH`, `EXELSE`, `EXPOP`, `EXANY`, `BARRIER`, `QBALLOT`, `QSHUF` |

Custom instructions use the R format (funct7, rs2, rs1, funct3, rd) or R4 (rs3 in 31..27,
funct2 in 26..25), so every register field sits where RISC-V puts it. Proposed values, fixed
when the opcode file gets them:

| Instruction | Opcode | Format | funct3 | funct7 / funct2 | Operands |
|---|---|---|---|---|---|
| `FRCP`, `FRSQ`, `FEXP2`, `FLOG2` | 0x0B | R | 0 | 0x00..0x03 | rd, rs1 |
| `DDX`, `DDY` | 0x0B | R | 1 | 0x00, 0x01 | rd, rs1; rs2 field: 0 coarse, 1 fine |
| `TEX` | 0x2B | R4 | 0 | 0 | rd (group of 4), rs1 = u, rs2 = v, rs3 = texture descriptor address |
| `TEXA` | 0x2B | R4 | 0 | 1 | rs1 = w or layer, rs2 = lod or bias, rs3 = dref |
| `TEXS` | 0x2B | R | 1 | 0x00 | rs1 = sampler descriptor address, rs2 = control word |
| `TLD` | 0x2B | R | 2 | 0x00 | rd; rs2 field = word |
| `ZTEST` | 0x2B | R | 3 | 0x00 | — |
| `SOUT` | 0x5B | R | 0 | 0x00 | rs1 = value; rs2 and rd fields = output component |
| `FATTR` | 0x5B | R | 1 | 0x00 | rd (group of 3); rs1 and rs2 fields = attribute |
| `EXPUSH`, `EXELSE`, `EXPOP`, `EXANY` | 0x7B | R | 0 | 0x00..0x03 | as v1 |
| `BARRIER` | 0x7B | R | 1 | 0x00 | — |
| `QBALLOT`, `QSHUF` | 0x7B | R | 2 | 0x00, 0x01 | see "Subgroups" |

`TEXS` sets the sampler descriptor and the control word for the `TEX`s after it: v1's word
without the descriptor indices (operation, gather component, compare, LOD mode, offsets), since
funct3 is too small for every combination. An explicit LOD, a bias, a layer or a depth reference
comes with a `TEXA` before the `TEX`. The texture unit reads the descriptors at the addresses and
keeps them in a cache tagged by address; v1's `TEX` forms its addresses from `TEX_DESC_BASE` and
`SAMPLER_DESC_BASE` until it goes.

## Base instructions

**RV32I**, all of it except the byte and halfword stores (`SB`, `SH`, reserved), `ECALL`,
`EBREAK` and `FENCE.TSO` (no-ops). `JALR` is decoded because RV32I has it; borgc inlines every
function and does not emit it.

**Zmmul**: `MUL`, `MULH`, `MULHU`, `MULHSU`. v1 has the low product only; the high half is what
`umulExtended` and 64-bit emulation need.

**Zbb, a subset**: `ANDN`, `ORN`, `XNOR`, `MIN`, `MINU`, `MAX`, `MAXU`, `SEXT.B`, `SEXT.H`,
`ZEXT.H`, `CLZ`, `CTZ`, `CPOP`, `ROL`, `ROR`, `RORI`, `REV8`: `findLSB`, `findMSB`, `bitCount`,
`bitfieldReverse`, integer min/max/clamp and the robust-access clamp (below).

**Zicond**: `CZERO.EQZ`, `CZERO.NEZ`. A select is `CZERO.NEZ t, a, c; CZERO.EQZ u, b, c; OR d, t,
u`, three instructions, exact for NaN and infinity where v1's `else + c·(then − else)` is not.

**Zfinx**, FP32 in the x registers: `FADD.S`, `FSUB.S`, `FMUL.S`, `FMADD.S`, `FMSUB.S`,
`FNMADD.S`, `FNMSUB.S`, `FMIN.S`, `FMAX.S`, `FSGNJ.S`, `FSGNJN.S`, `FSGNJX.S`, `FEQ.S`, `FLT.S`,
`FLE.S`, `FCLASS.S`, `FCVT.W.S`, `FCVT.WU.S`, `FCVT.S.W`, `FCVT.S.WU`. Not implemented: `FDIV.S`,
`FSQRT.S`, the `fcsr` flags, and rounding modes other than RNE (arithmetic) and RTZ (conversion
to integer).

**Zhinxmin**: `FCVT.S.H`, `FCVT.H.S`, replacing `H2F` and `F2H`.

**Zicsr**, `CSRRS` and `CSRRW` on the system-value CSRs only, in the custom user range
0x800-0x8FF (read-write) and 0xCC0-0xCFF (read-only).

**Branches.** `BEQ`..`BGEU` and `JAL` move the whole quad; as in v1 the condition comes from the
lowest active lane and a sticky status bit records lanes that disagreed. Divergent control flow
uses the execution mask (`EXPUSH`, `EXELSE`, `EXPOP`, `EXANY`) as in v1.

## Special functions

`FRCP`, `FRSQ`, `FEXP2` and `FLOG2` share one unit: a table of segments and a quadratic
interpolation per function (as NVIDIA's MUFU), reached by the lanes in turn. Vulkan's precision:
`exp2` 3 + 2·|x| ULP; `log2` 3 ULP, or 2⁻²¹ absolute in [0.5, 2]; `pow`, `exp`, `log` inherit
from them. borgc compiles `pow(x, y)` as `FEXP2(y · FLOG2(x))`, with no idiom matching, and
`sin`, `cos` (2⁻¹¹ absolute in [−π, π]), `tan` and the inverse functions as range reduction plus
a polynomial on `FMA`. A hardware `SIN`/`COS` is added only if measurements ask for it.

## Registers

32 registers of 32 bits per lane; `x0` reads as zero. **No register has a role.** The hardware
reads and writes registers only where an instruction names them.

| v1 fixed role | v2 |
|---|---|
| r30/r31 pixel centre (fragment) | CSR `frag_x`, `frag_y` (x + 0.5, y + 0.5), `frag_z`, `frag_w` |
| r30/r31 VertexIndex, InstanceIndex | CSR `vertex_index`, `instance_index` |
| r30/r31 invocation IDs, u29-u31 WorkgroupID | CSR `local_index`, `local_id` (packed as v1), `group_id_x/y/z` |
| r5-r7 barycentrics, r8 1/w | CSR `bary0`..`bary2`, `inv_w` |
| r0-r4, r9-r14 raster ROM state, r29 FragCoord.z | rasterizer-internal; the shader reads `frag_z` |
| r24 alpha, r26-r28 colour, r29 depth | CSR writes `out_c0`..`out_c3` (R, G, B, A or raw words), `out_depth` |
| r25 kill | CSR write `discard` (sticky) |
| r19 sample mask, `SMASK` | CSR `sample_mask` (read: coverage; write: output mask) |
| `ATTIDX` | CSR `attachment` |
| r17-r19 and other constant presets, u20-u31 windows | none: immediates and constant-buffer loads |
| r12-r14 workgroup base (borgc) | a register like any other |
| root of the descriptors (v1: the base registers) | CSR `root` |

A CSR read is `CSRRS rd, csr, x0`, a write `CSRRW x0, csr, rs1`. The raster and draw ROMs keep
their state in registers of their own, not the shader's; that removes the snooping of
`pipeWrite` addresses in `BorgShaderDispatcher` and `BorgDrawWalker`, and with it the collision
class that killed the r21-r24 read-back port.

TEX and FATTR results stay groups of 4 and 3 consecutive registers, which the compiler places.

## Constants

v1 reads at most one operand per instruction from a 32-word window the driver stages per draw
(`DRAW_VS_CONST`, `DRAW_FS_CONST`) and spills the rest into a pool. v2 drops the window:

- small integers and floats with zero low bits: one `ADDI` or `LUI`;
- any other 32-bit constant: `LUI` + `ADDI`, or a load from the shader's constant buffer, whose
  address is in the root table;
- borgc hoists constants out of loops and keeps hot ones in registers.

This removes the operand substitution in every lane, the uniform memory (`mem_32x32`, 124,369 µm²
on Wafer) and borgc's pool placement.

## Memory

### Addresses and accesses

32-bit byte addresses, as v1. `LW`, `LH`, `LHU`, `LB`, `LBU` and `SW` at `rs1 + imm12`, per lane,
the lanes in turn as v1. Word accesses are aligned (the low bits are ignored). Helper, discarded
and early-test-failed lanes drop their stores as in v1, except stores to the private window
(spills, local arrays), which they still perform.

**Prepared, not built**: `SB` and `SH`. They change part of a word; another invocation may write
the rest of the word at the same time, which Vulkan allows, so a read-modify-write that is not
atomic loses its writes. They need byte enables on `GpuMemIO` down to the SDRAM's DQM (or the
read-modify-write done inside the atomic unit, below). `GpuMemIO` gets the byte-mask field now,
all ones, so the extensions that need byte stores (`VK_KHR_8bit_storage`, `16bit_storage`) need
no interface change.

### Buffers

A buffer descriptor is two words in memory: base address and range in bytes. The shader loads
them from its set and accesses `base + offset`. `robustBufferAccess` (required by Vulkan 1.0) is
a clamp before each access, `MINU off, off, range_minus_size`; `robustBufferAccess2`'s zero for
an out-of-range read is `SLTU` + `CZERO.NEZ`.

Texel buffers have a texture descriptor (1D, linear) and are sampled with `TEX`, as v1.

### Descriptors and the root table

A descriptor set is a block of device memory in hardware format. `vkUpdateDescriptorSets` writes
it and `vkCopyDescriptorSets` copies bytes:

| Descriptor | Bytes | Layout |
|---|---|---|
| sampled image, storage image, input attachment | 64 | the v1 texture descriptor (`B2`) |
| sampler | 16 | the v1 sampler descriptor |
| combined image sampler | 80 | texture, then sampler |
| uniform or storage buffer | 8 | base address, range |
| texel buffer | 64 | a 1D linear texture descriptor |

Per draw or dispatch the driver writes a **root table**: the address of each bound set (dynamic
offsets applied into a copy of the set's buffer descriptors), the push constants and the
shader's constant buffer. CSR `root` holds its address. A texture access:

```
csrrs  t0, root, x0        # once per shader
lw     t1, 4(t0)           # set 1, hoisted
addi   t2, t1, 80*k        # binding k, or a computed index
tex    x8, u, v, t2        # texture descriptor at t2; the sampler follows it
```

A combined image sampler's sampler is at `desc + 64`; a separate sampler comes with
`TEXS t3, ctl`, t3 holding its address. Vertex attribute fetch (v1: descriptors at slot 128 + n
of the texture table) takes its descriptors from addresses in the root table too.

### Descriptor cache

The texture unit keeps the last 2 (Wafer) or 8 (ULX3S) texture descriptors and 2 sampler
descriptors, fully associative, tagged by address, invalidated at every draw and dispatch start
and by `FENCE`. A set does not change while a draw that uses it runs (Vulkan forbids it without
`UPDATE_AFTER_BIND`). It replaces today's index-tagged cache (`texDescCacheLog2`).

### Atomics and ordering

- **Atomics**: `AMOADD.W`, `AMOSWAP.W`, `AMOAND.W`, `AMOOR.W`, `AMOXOR.W`, `AMOMIN[U].W`,
  `AMOMAX[U].W` (Zaamo) and `AMOCAS.W` (Zacas), done by one read-modify-write unit at the memory
  port, atomic against every master. Lanes take turns; each gets its own old value. No `LR/SC`.
- **Ordering**: a lane's accesses complete in program order (the core stalls on each, as v1).
  `FENCE` waits for the quad's outstanding writes and orders them against other quads and
  masters. With no caches in front of memory it costs a cycle until the pipeline (B9) lets
  accesses overlap; it is where Vulkan's availability and visibility operations compile to.

## sRGB attachments

The tile loader decodes an sRGB attachment's 8-bit values to linear (a 256-entry table, as the
sampler's `srgbRom`); the tile holds linear colour, so `TLD` and blending see linear values; the
flusher encodes linear to sRGB on the way out (an encode table). `FSRGB` and borgvk's
shader-side decode table (vertex attribute slot 41, `BORGVK_SRGB_SLOT`) go. A shader that
computes `linearToSrgb` itself, like vkcube's `cube.frag`, compiles to `FLOG2` and `FEXP2` like
any `pow`.

## Protection

Every context (one per DRM file, set by the kernel driver before it starts a job) has a base and
a limit register for data, and a pair for code. An access outside `[base, limit)` is dropped (a
load returns 0) and sets a sticky fault bit the kernel driver reads. It covers loads, stores,
atomics, the texture unit's descriptor and texel reads, FATTR and SOUT records and instruction
fetch: comparators on the core's memory port and on the sampler's. Only the kernel driver
writes these registers. An Sv32 MMU with a small TLB stays possible later; base and limit cover
one process's GPU memory as one carve-out (`B5`), which is what the board has.

## Subgroups

The quad's 4 lanes are a subgroup of 4 in every stage.

| Operation | v2 |
|---|---|
| `subgroupBallot`, `Elect`, `All`, `Any`, `AllEqual` | `QBALLOT rd, rs1`: bit i is set when lane i is active and its rs1 is non-zero, the same value in every lane (v1's exec-mask logic computes it already, `perLaneTrue`). Elect is `CTZ` of the active mask. |
| `subgroupBroadcast`, `Shuffle`, `ShuffleXor/Up/Down`, quad swaps | `QSHUF rd, rs1, rs2`: each lane reads rs1 of the lane named by its rs2 (0-3). The DDX/DDY path already moves every lane's operand to every lane through a fixed 4:1 mux; `QSHUF` makes the select per lane and skips the subtraction. |
| arithmetic reductions and scans | two `QSHUF` steps plus the operation, from borgc |
| `subgroupBarrier`, `subgroupMemoryBarrier` | nothing / `FENCE` |

## Configuration per target

| | Wafer (minimal) | ULX3S (pipelined, B9) |
|---|---|---|
| Register file | flops, one read port (operands read in turn) | LUTRAM or BRAM, three read ports |
| `CLZ`, `CTZ`, `CPOP`, `MULH*` | iterative, several cycles | one cycle |
| Special functions | one unit per quad | one unit per quad |
| Descriptor cache | 2 texture + 2 sampler | 8 + 2 |
| Quads in flight | 1 | 1, then more (B9) |

Only latencies differ, so one borgc blob runs on every target.

## Area budget (ULX3S LUTs, estimates)

Baseline 2026-10-07: 71,312 of 83,640 LUTs (85%) with HDMI, about 12,300 free; the vkcube SoC
was at 95% in September. Costs per lane count four times. Each estimate is replaced by a
measurement when its step lands; a step over budget gets its RTL redesigned.

| Piece | Estimate | Note |
|---|---|---|
| RISC-V decode, immediates, `x0`, B/J formats | +1,000 to +1,500 | next to the v1 decode, which then goes |
| Uniform window and operand substitution | −600 to −1,000 | per lane and in the core |
| r30/r31 pseudo-registers, output snooping | −300 to −600 | replaced by a CSR mux (+300) |
| Zbb subset, 4 lanes | +1,500 to +2,000 | mostly `CLZ`, `CTZ`, `CPOP` |
| Zicond, 4 lanes | +100 | |
| Zfinx compares, min/max, sign injection, class, 4 lanes | +800 to +1,200 | |
| Zmmul high half, 4 lanes | +200 | and 4 more DSPs (75 of 156 used) |
| `FEXP2`, `FLOG2` tables and interpolation | +600 to +1,200 | shared unit; `FSRGB` removed (−) |
| sRGB decode and encode in the tile path | +300 to +600 | the slot-41 table goes |
| Byte and halfword loads, address adder | +300 | |
| Atomic unit | +500 to +800 | |
| Base and limit | +150 | |
| Address-tagged descriptor cache | +400 to +800 | replaces the index-tagged one |
| `QBALLOT`, `QSHUF` | +400 | generalizes the DDX/DDY mux |
| **Total** | **+4,500 to +8,000** | before the pipeline (B9) |

Wafer: the same steps measured in µm²; the uniform memory (124,369 µm²) and the window muxes
pay for part of it.

## Migration

Gradual, on main, alongside the CTS work. v1 uses only major opcodes 0x00 and 0x04, and every
RISC-V major opcode ends in binary 11, so new instructions decode next to the old ones without a
mode bit and without a hard switch. Each step:

1. adds one group to the opcode file and the RTL, with Chisel unit tests;
2. switches borgc (and borgvk or `borg_core` where the step needs it) to the new form;
3. passes direct_sim, the borgc corpus, the quick driver corpus and the cube, measures LUTs, ULX3S
   fit and Wafer area;
4. deletes the v1 instructions or roles it replaced, in a commit of its own.

Order, cheapest and most independent first:

1. OP and OP-IMM (`ADD`..`AND`, `SLT[U]`, shifts, immediates) and `x0`; `LUI`, `AUIPC`.
2. `BEQ`..`BGEU`, `JAL`; then `BRZ`, `BRNZ`, `JMP` go.
3. Zmmul, Zicond, the Zbb subset.
4. Zfinx arithmetic, compares, conversions, Zhinxmin; then `FNEG`, `FSTEP`, `I2F`, `F2I`,
   `F2H`, `H2F`, `FMOV` and the v1 integer ops go.
5. `FEXP2`, `FLOG2`, and borgc's `sin`/`cos` polynomials.
6. sRGB attachments in the tile path; then `FSRGB` and the slot-41 table go.
7. CSRs for system values, one role per step (r30/r31, invocation IDs, barycentrics, FragCoord);
   then the pseudo-registers go.
8. Fragment outputs through CSRs; then the output snooping goes.
9. Constants by immediates and a constant buffer; then the uniform window goes.
10. `LW`/`SW` by address and the root table for buffers; then `LOAD`, `STORE` and `LS_BASE` go.
11. Addressed `TEX`, `TEXS` and the address-tagged descriptor cache; then the index control word
    and `TEX_DESC_BASE`, `SAMPLER_DESC_BASE` go.
12. Atomics; then borgc's lane-serial atomics go.
13. Base and limit.
14. `QBALLOT`, `QSHUF`, and the subgroup feature bits in borgvk.
15. The custom ops move to their custom-0..3 encodings, and opcodes 0x00 and 0x04 are empty.

Step 8 runs right after step 1, with the CSR access it needs from step 7: on 2026-10-22 both
ULX3S builds miss their clock in the output snoop (vkcube SoC 16.9 MHz against 25, Linux build
16.0 against 18.75, paths ending in the dispatcher's `frag_b_0`). Step 10 follows it: buffers
through addresses replace compute's 32 binding windows, which fail conformance cases.

Then the pipeline (B9).
