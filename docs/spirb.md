# SPIR-B: Borg Shader Binary Format

SPIR-B is a compact binary format for Borg shader programs. It encodes
instructions, register assignments, and compile-time constants into a
blob: borgc writes it when a pipeline is created, and borgvk's list builder loads it.

## Byte layout

All multi-byte fields are **little-endian**. Register indices are single bytes.

```text
Offset  Size       Field
──────  ─────────  ────────────────────────────
0       1 byte     num_instructions   (N, low 8 bits)
1       1 byte     num_uniforms       (U)
2       1 byte     num_attributes     (A)
3       1 byte     num_outputs        (O)
4       1 byte     num_consts         (C)
5       1 byte     extensions         (bit 0: draw extension follows; bits 7:1: N >> 8)
6       N × 4      instructions       uint32_le[]
6+N*4   U          uniform_regs       uint8[]
...     A          attribute_regs     uint8[]
...     O          output_regs        uint8[]
...     C          const_regs         uint8[]
...     C × 4      const_vals         uint32_le[]
```

**Total size** = `6 + N*4 + U + A + O + C + C*4` bytes, plus the draw
extension below when `extensions` bit 0 is set.

### Draw extension

A shader compiled for the draw front end (`docs/B1_geometry_front_end.md`)
sets `extensions` bit 0 and appends:

```text
Size       Field
─────────  ────────────────────────────
1 byte     num_varyings       (V)
1 byte     num_window         (W)
W          window_regs        uint8[]
W × 4      window_vals        uint32_le[]
```

`num_varyings` is the number of varying components a vertex shader writes
with `SOUT` (0 in a fragment shader). It sizes the triangle records, which
take `48 + 3 * V` words. The window is the shader's constant window:
`window_regs[i]` is a u-index, u25-u31 in a vertex shader and u20-u31 in a
fragment shader, and `window_vals[i]` its bits. The list builder writes each
value into the render list's constant window (`DRAW_VS_CONST` or `DRAW_FS_CONST`)
at `4 * (u - 25)` or `4 * (u - 20)`. Legacy blobs have `extensions` = 0 and are unchanged.

`const_vals` is `uint32_le` (widened 2026-09-08 from `uint16_le`) so the format
can eventually carry a real FP32 constant -- `borgc` has no FP32 codegen path
yet, so every current producer still writes an FP16 bit pattern zero-extended
into the low 16 bits. This is a wire-format widening only; see
`mesa/src/borg/compiler/encode.rs`'s `emit_blob` doc comment for the full
note. borgvk reads the draw extension in `list.rs` (`draw_ext`).

The instruction list does **not** include the implicit halt word (0x0000);
the list builder appends it when it copies the code into the render list.

## Shader transport

The borgvk driver (`mesa/src/borg/vulkan/`) compiles, with `borgc`, the app's real
vertex/fragment shaders at pipeline creation. A draw hands each blob to the list
builder as a command (`list::Cmd::Shader`, the stage and the blob, up to
`BORGVK_SHADER_BLOB_MAX` bytes); the builder (`list.rs`) reads the draw extension
itself (`draw_ext`) and copies the code into the render list.

## Examples

### Rasterize shader (21 bytes)

```text
02 00 04 01 00 00       Header: 2 instrs, 0 uniforms, 4 attrs, 1 output, 0 consts
XX XX XX XX             Instructions: 2 × uint32_le (32-bit RISC-V encoded)
XX XX XX XX
01 02 03 04             Attribute regs: r1, r2, r3, r4
00                      Output reg: r0
```

### Frag shader (38 bytes)

```text
06 04 03 01 00 00       Header: 6 instrs, 4 unis, 3 attrs, 1 output, 0 consts
[6 × 4 = 24 bytes]     Instructions: 6 × uint32_le
02 05 06 07             Uniform regs: r2, r5, r6, r7
01 03 04                Attribute regs: r1, r3, r4
00                      Output reg: r0
```

## Toolchain

| Tool              | Input      | Output       |
|-------------------|------------|--------------|
| `glslang` or the application | GLSL | SPIR-V |
| borgc (in borgvk, or `borgc-cli`) | SPIR-V | SPIR-B blob |
| borgvk's list builder (`list.rs`) | SPIR-B blob | code and constant windows in the render list |
| the shader core | render list | runs the code through the instruction cache |
