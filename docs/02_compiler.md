# The Shader Compiler

The Borg toolchain compiles standard GLSL shaders into compact binary
blobs that run on the FP16 hardware. The pipeline has four stages.

## Pipeline Overview

```text
shader.vert / shader.frag
        │
        ▼  glslangValidator -V
    shader.spv          (SPIR-V binary, Khronos standard)
        │
        ▼  spirv-dis
    shader.spvasm        (SPIR-V text disassembly)
        │
        ▼  spirv_compiler.py   ← semantic translation
    shader.s             (Borg pseudo-assembly)
        │
        ▼  borg_backend.py     ← mechanical lowering
    shader.borg          (SPIR-B binary blob)
```

The first two steps use standard Khronos tools. The last two are
Borg-specific and described below.

## Stage 1 vs Stage 2 — What Each Script Does

These two scripts have completely different responsibilities and it is
important not to confuse them.

### `spirv_compiler.py` — Semantic Translation

This script *understands* SPIR-V. It knows about the SPIR-V type system
(image types, sampled-image types, composite types, storage classes,
decorations) and maps each high-level SPIR-V concept to a virtual
register or pseudo-assembly instruction.

- **Input**: `shader.spvasm` (text output of `spirv-dis`)
- **Output**: `shader.s` (Borg pseudo-assembly with `@borg` annotations)
- **Knows about**: SPIR-V SSA IDs, composite types, uniform structs,
  texture samplers, storage classes (`Input`, `Output`, `Uniform`,
  `UniformConstant`), `OpImageSampleImplicitLod`, etc.
- **Does NOT know about**: physical register numbers, instruction
  bit-patterns, or hardware constraints.

Virtual registers are named `f0`, `f1`, … and are unlimited — the script
never worries about running out of hardware registers.

### `borg_backend.py` — Mechanical Lowering

This script knows nothing about SPIR-V. It takes the pseudo-assembly
produced by `spirv_compiler.py` and performs two purely mechanical tasks:

1. **Register allocation** — runs Poletto & Sarkar (1999) linear-scan
   allocation to assign each virtual register (`f0`, `f1`, …) to a
   physical Borg hardware register (`r0`–`r29`). Uniforms go to the
   32-entry uniform buffer instead of the GPR file.

2. **Instruction encoding** — encodes each pseudo-assembly instruction
   as a 32-bit RISC-V R-type word and serialises everything into the
   `.borg` SPIR-B binary.

- **Input**: `shader.s` (pseudo-assembly)
- **Output**: `shader.borg` (SPIR-B binary blob)
- **Knows about**: physical register indices, bit-field layouts,
  `funct7` / `funct3` encoding, the SPIR-B file format.
- **Does NOT know about**: SPIR-V, type systems, image samplers, or
  semantic shader concepts.

## Stage 1: SPIR-V → Pseudo-Assembly

`spirv_compiler.py` runs two passes over the `.spvasm` input:

**Pass 1 (metadata)** collects names, constants, struct types, storage
classes, decorations, and sampler variable IDs from declarative opcodes
like `OpName`, `OpDecorate`, `OpConstant`, `OpTypeImage`,
`OpTypeSampledImage`, and `OpVariable`.

**Pass 2 (codegen)** walks executable opcodes and emits instructions:

| SPIR-V Opcode | Pseudo-Assembly | Notes |
| --- | --- | --- |
| `OpFMul` | `fmul.s rd, ra, rb` | Scalar multiply |
| `OpFAdd` | `fadd.s rd, ra, rb` | Scalar or vector add |
| `OpExtInst FMA` | `fmadd.s rd, ra, rb, rc` | Fused multiply-add |
| `OpFNegate` | `fneg.s rd, rs` | Sign flip (host-side) |
| `OpExtInst Sin` | `fsin.s rd, rs` | Lookup table (host-side) |
| `OpExtInst Cos` | `fcos.s rd, rs` | Lookup table (host-side) |
| `OpMatrixTimesVector` | 2×fmul + 2×fmadd | 2D rotation expansion |
| `OpImageSampleImplicitLod` | `ftex.s rd, rs_u, rs_v` | Texture sample; rd=R, rd+1=G, rd+2=B (implicit) |

`OpLoad` of a sampler object (`UniformConstant` storage class) emits no
code — it is tracked internally as a sampler reference consumed by
`OpImageSampleImplicitLod`.

The compiler emits `@borg` annotations at the end of the output that
declare which virtual registers serve as uniforms, attributes, outputs,
FTEX channels, or constants. These drive register allocation and the
SPIR-B metadata tables in the backend.

## Stage 2: Pseudo-Assembly → SPIR-B

`borg_backend.py` lowers the pseudo-assembly into two outputs:

1. **Host code** (C) for operations the Borg FPU cannot perform:
   sin/cos lookups, sign-bit negation, register loads/stores.

2. **Borg IMEM instructions** (32-bit encoded) for hardware-accelerated
   `fmul`, `fadd`, `fmadd`, and `ftex` operations.

### Register Allocation

The backend runs Poletto & Sarkar linear-scan allocation:

- **Uniforms** → 32-entry uniform buffer (`u0`–`u31`). They are read
  via the `funct3` field and do not consume GPR slots.
- **FTEX implicit outputs** (`ftex_implicit` annotations) → must be
  allocated to GPRs at `rd+1` and `rd+2` relative to the FTEX result
  register, since hardware writes them implicitly.
- **Everything else** → GPR pool (`r0`–`r29`).

### Instruction Encoding

Each instruction is a 32-bit word using standard RISC-V R-type encoding:

| Mnemonic | funct7 | rs2 | rs1 | funct3 | rd | opcode |
| --- | --- | --- | --- | --- | --- | --- |
| `fadd` | `0x00` | rs2 | rs1 | mode | rd | `1010011` |
| `fmul` | `0x04` | rs2 | rs1 | mode | rd | `1010011` |
| `fmadd` | rs3 | rs2 | rs1 | mode | rd | `1000011` |
| `fneg` | `0x06` | `00000` | rs1 | mode | rd | `1010011` |
| `fstep` | `0x08` | `00000` | rs1 | mode | rd | `1010011` |
| `frcp` | `0x0A` | `00000` | rs1 | mode | rd | `1010011` |
| `ftex` | `0x0C` | rs2(V) | rs1(U) | mode | rd(R) | `1010011` |

The `funct3` field (`mode`) selects which operand reads from the uniform
buffer: `0`=all GPR, `1`=rs1, `2`=rs2, `3`=rs3. This allows one
uniform operand per instruction without an extra register-file port.

A 32-bit word of `0x00000000` halts execution.

For `ftex`: `rd` receives texel R; hardware implicitly writes texel G to
`rd+1` and texel B to `rd+2` on the two clock cycles following `texDone`.

## The SPIR-B Binary Format

The final `.borg` file is a self-describing blob with everything the
firmware needs to load and run a shader:

```text
Offset  Size       Field
──────  ─────────  ────────────────────────────
0       1 byte     num_instructions   (N)
1       1 byte     num_uniforms       (U)
2       1 byte     num_attributes     (A)
3       1 byte     num_outputs        (O)
4       1 byte     num_consts         (C)
5       1 byte     reserved
6       N × 4      instructions       uint32_le[]
6+N*4   U          uniform_regs       uint8[]
...     A          attribute_regs     uint8[]
...     O          output_regs        uint8[]
...     C          const_regs         uint8[]
...     C × 2      const_vals         uint16_le[]
```

At runtime, `spirb_parse()` loads this into memory and the driver uses
the register maps to bind shader inputs/outputs before each invocation.

## Build Integration

The shader compiler runs as part of `make` in `software/borg/compiler/`.
The three `.borg` files (vert, frag, rasterize) are embedded into the
firmware binary via `xxd -i`, making the shaders part of the ROM image
with no filesystem needed.

## Three Shaders

The cube demo uses three shaders:

**Vertex shader** (`shader.vert`) — Applies a 2D rotation matrix to
vertex positions using sin/cos uniforms. Produces 5 IMEM instructions
and 26 bytes of SPIR-B.

**Rasterize shader** (`rasterize.s`) — Evaluates one edge function per
call. Hand-written in pseudo-assembly (not compiled from GLSL) since the
edge test is a single dot product. Produces 2 IMEM instructions and
15 bytes of SPIR-B.

**Fragment shader** (`shader.frag`) — Computes barycentric weights from
edge values, interpolates UV coordinates and per-face vertex color
(lighting factor), samples the texture via `ftex` (`OpImageSampleImplicitLod`),
and modulates: `outRGB = texel.rgb × vertexColor.rgb`. Also interpolates
depth (Z) for the tile-buffer Z-test. Produces ~15 IMEM instructions.
