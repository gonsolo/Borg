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
5       1 byte     reserved           (must be 0)
6       N × 4      instructions       uint32_le[]
6+N*4   U          uniform_regs       uint8[]
...     A          attribute_regs     uint8[]
...     O          output_regs        uint8[]
...     C          const_regs         uint8[]
...     C × 2      const_vals         uint16_le[]
```

**Total size** = `6 + N*4 + U + A + O + C + C*2` bytes.

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
