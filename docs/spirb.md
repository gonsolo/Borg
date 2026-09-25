# SPIR-B: Borg Shader Binary Format

SPIR-B is a compact binary format for Borg shader programs. It encodes
instructions, register assignments, and compile-time constants into a
runtime-loadable blob, decoupling shader changes from firmware recompilation.

## Byte layout

All multi-byte fields are **little-endian**. Register indices are single bytes.

```text
Offset  Size       Field
──────  ─────────  ────────────────────────────
0       1 byte     num_instructions   (N)
1       1 byte     num_uniforms       (U)
2       1 byte     num_attributes     (A)
3       1 byte     num_outputs        (O)
4       1 byte     num_consts         (C)
5       1 byte     extensions         (bit 0: draw extension follows)
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
fragment shader, and `window_vals[i]` its bits. The firmware writes each
value to `DRAW_VS_CONST` or `DRAW_FS_CONST` plus `4 * (u - 25)` or
`4 * (u - 20)`. Legacy blobs have `extensions` = 0 and are unchanged.

`const_vals` is `uint32_le` (widened 2026-09-08 from `uint16_le`) so the format
can eventually carry a real FP32 constant -- `borgc` has no FP32 codegen path
yet, so every current producer still writes an FP16 bit pattern zero-extended
into the low 16 bits. This is a wire-format widening only; see
`mesa/src/borg/compiler/encode.rs`'s `emit_blob` doc comment and
`software/borg/borg_spirb.h`'s `const_vals` doc comment (same commit) for the
full note.

The instruction list does **not** include the implicit halt word (0x0000);
the firmware appends it when loading IMEM.

## Shader transport

Two paths exist depending on the use case:

**Baked defaults** (`borg_kernel.c`): a rasterize/vertex/fragment shader blob
is embedded as a static byte array in `compiler/shader_blobs.h`, loaded at
boot via `spirb_parse(code, &shader)` so the GPU pipeline is valid before any
borgvk upload arrives. The rasterize stage stays on this baked blob
permanently — it is fixed-function, not app-derived.

**borgvk driver** (`mesa/src/borg/vulkan/`): `borgc` compiles the app's real
vertex/fragment shaders at Vulkan submit time and sends each blob via a serial
packet (marker `0xB0`):
`marker(1B) + stage(1B) + len(2B LE) + blob padded to BORGVK_SHADER_BLOB_MAX + checksum`.
`borg_kernel.c` drains this fixed-length packet and calls `spirb_parse()` on
the received blob, overriding the baked vertex/fragment defaults.

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
| `spirv_compiler`  | `.vert`    | `.spvasm`    |
| `borg_backend.py` | `.s`       | `.borg` / `.borg.h` |
| host `triangle.py`| `.borg`    | DRAM write  |
| firmware          | DRAM read | Borg IMEM + regs |
