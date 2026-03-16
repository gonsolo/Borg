# SPIR-B: Borg Shader Binary Format

SPIR-B is a compact binary format for Borg shader programs. It encodes
instructions, register assignments, and compile-time constants into a
runtime-loadable blob, decoupling shader changes from firmware recompilation.

## Byte layout

All multi-byte fields are **little-endian**. Register indices are single bytes.

```
Offset  Size       Field
──────  ─────────  ────────────────────────────
0       1 byte     num_instructions   (N)
1       1 byte     num_uniforms       (U)
2       1 byte     num_attributes     (A)
3       1 byte     num_outputs        (O)
4       1 byte     num_consts         (C)
5       1 byte     reserved           (must be 0)
6       N × 2      instructions       uint16_le[]
6+N*2   U          uniform_regs       uint8[]
...     A          attribute_regs     uint8[]
...     O          output_regs        uint8[]
...     C          const_regs         uint8[]
...     C × 2      const_vals         uint16_le[]
```

**Total size** = `6 + N*2 + U + A + O + C + C*2` bytes.

The instruction list does **not** include the implicit halt word (0x0000);
the firmware appends it when loading IMEM.

## PSRAM transport

The host writes shader blobs to PSRAM before the vertex/uniform payload.
Each shader blob is preceded by a 2-byte little-endian length prefix:

```
[blob_len (uint16_le)] [blob bytes ...] [shader data ...]
```

The firmware reads `blob_len`, parses the blob, then processes the
remaining PSRAM data using the register maps from the parsed shader.

## Examples

### Rasterize shader (15 bytes)

```
02 00 04 01 00 00       Header: 2 instrs, 0 uniforms, 4 attrs, 1 output, 0 consts
10 44 20 83             Instructions: 0x4410, 0x8320
01 02 03 04             Attribute regs: r1, r2, r3, r4
00                      Output reg: r0
```

### Vert shader (26 bytes)

```
05 03 02 02 01 00       Header: 5 instrs, 3 uniforms, 2 attrs, 2 outputs, 1 const
40 46 50 87 31 46       Instructions: 0x4640, 0x8750, 0x4631, ...
41 97 00 02
03 04 05                Uniform regs: r3, r4, r5
06 07                   Attribute regs: r6, r7
00 01                   Output regs: r0, r1
02                      Const reg: r2
00 C0                   Const val: 0xC000 (-2.0 in FP16)
```

## Toolchain

| Tool              | Input      | Output       |
|-------------------|------------|--------------|
| `spirv_compiler`  | `.vert`    | `.spvasm`    |
| `borg_backend.py` | `.s`       | `.borg` / `.borg.h` |
| host `triangle.py`| `.borg`    | PSRAM write  |
| firmware          | PSRAM read | Borg IMEM + regs |
