# The Shader Compiler

The Borg toolchain compiles standard GLSL shaders into compact binary
blobs that run on the FP16 hardware. The pipeline has three stages,
each implemented as a small Python script.

## Pipeline Overview

```text
shader.vert / shader.frag
        │
        ▼  glslangValidator -V
    shader.spv          (SPIR-V binary)
        │
        ▼  spirv-dis
    shader.spvasm        (SPIR-V text)
        │
        ▼  spirv_compiler.py
    shader.s             (Borg pseudo-assembly)
        │
        ▼  borg_backend.py
    shader.borg          (SPIR-B binary blob)
    shader.borg.h        (C header, optional)
```

The first two steps use standard Khronos tools. The last two are
Borg-specific and described below.

## Stage 1: SPIR-V → Pseudo-Assembly

`spirv_compiler.py` translates SPIR-V text into pseudo-assembly using
RISC-V F-extension mnemonics. It runs two passes over the input:

**Pass 1 (metadata)** collects names, constants, struct types, storage
classes, and decorations from declarative opcodes like `OpName`,
`OpDecorate`, `OpConstant`, and `OpVariable`.

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

The compiler emits `@borg` annotations at the end of the output that
declare which virtual registers serve as uniforms, attributes, outputs,
or constants. These drive both register allocation and the SPIR-B
metadata tables.

## Stage 2: Pseudo-Assembly → SPIR-B

`borg_backend.py` lowers the pseudo-assembly into two outputs:

1. **Host code** (C) for operations the Borg FPU cannot perform:
   sin/cos lookups, sign-bit negation, register loads/stores.

2. **Borg IMEM instructions** (16-bit encoded) for hardware-accelerated
   `fmul`, `fadd`, and `fmadd` operations.

### Register Allocation

The backend maps virtual register names (f0, f1, ...) to physical Borg
registers (r0–r7). Allocation follows a priority order:

1. **FMA accumulators first** — the `rs3` field in fmadd is only 2 bits,
   so accumulators must land in r0–r3.
2. **I/O registers next** — uniforms, attributes, and outputs must be in
   the MMIO-accessible range (r0–r7).
3. **Remaining operands** — filled in order of first appearance.

### Instruction Encoding

Each instruction is a 16-bit word loaded into IMEM:

| Format | Bits 15–14 | Bits 13–12 | Bits 11–8 | Bits 7–4 | Bits 3–0 |
| --- | --- | --- | --- | --- | --- |
| `fadd` | 00 | — | rs2 | rs1 | rd |
| `fmul` | 01 | — | rs2 | rs1 | rd |
| `fmadd` | 10 | rs3 | rs2 | rs1 | rd |
| `fneg` | 11 | 00 | — | rs1 | rd |
| `fstep` | 11 | 01 | — | rs1 | rd |

A word of 0x0000 halts execution.

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
6       N × 2      instructions       uint16_le[]
6+N*2   U          uniform_regs       uint8[]
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

The triangle application uses three shaders:

**Vertex shader** (`shader.vert`) — Applies a 2D rotation matrix to
vertex positions using sin/cos uniforms. Produces 5 IMEM instructions
and 26 bytes of SPIR-B.

**Rasterize shader** (`rasterize.s`) — Evaluates one edge function per
call. Hand-written in pseudo-assembly (not compiled from GLSL) since the
edge test is a single dot product. Produces 2 IMEM instructions and
15 bytes of SPIR-B.

**Fragment shader** (`shader.frag`) — Performs barycentric interpolation
of per-vertex attributes. Computes `result = (e0·c0 + e1·c1 + e2·c2) ×
inv_area`. Produces 4 IMEM instructions and 26 bytes of SPIR-B.
