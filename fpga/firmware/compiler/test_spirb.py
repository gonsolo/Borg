# SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""Round-trip test for SPIR-B binary format.

Verifies that emit_binary() produces a blob that, when parsed back,
matches the original header output.
"""

import struct
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from borg_backend import BorgBackend


def parse_spirb(blob):
    """Parse a SPIR-B blob back into component fields."""
    p = 0
    n_instr, n_uni, n_attr, n_out, n_const, _ = struct.unpack_from('<6B', blob, p)
    p += 6

    instrs = []
    for _ in range(n_instr):
        instrs.append(struct.unpack_from('<H', blob, p)[0])
        p += 2

    uniform_regs = list(blob[p:p + n_uni]); p += n_uni
    attribute_regs = list(blob[p:p + n_attr]); p += n_attr
    output_regs = list(blob[p:p + n_out]); p += n_out
    const_regs = list(blob[p:p + n_const]); p += n_const

    const_vals = []
    for _ in range(n_const):
        const_vals.append(struct.unpack_from('<H', blob, p)[0])
        p += 2

    assert p == len(blob), f"Parsed {p} bytes but blob is {len(blob)}"

    return {
        'n_instr': n_instr, 'n_uni': n_uni, 'n_attr': n_attr,
        'n_out': n_out, 'n_const': n_const,
        'instrs': instrs, 'uniform_regs': uniform_regs,
        'attribute_regs': attribute_regs, 'output_regs': output_regs,
        'const_regs': const_regs, 'const_vals': const_vals,
    }


def test_shader(name, asm_path):
    """Test that emit_binary round-trips correctly for a given shader."""
    with open(asm_path, 'r') as f:
        asm_text = f.read()

    backend = BorgBackend()
    backend.lower(asm_text)
    blob = backend.emit_binary()
    parsed = parse_spirb(blob)

    # Verify counts
    assert parsed['n_instr'] == len(backend.borg_instrs), f"{name}: instruction count mismatch"
    assert parsed['n_uni'] == len(backend.borg_uniforms), f"{name}: uniform count mismatch"
    assert parsed['n_attr'] == len(backend.borg_attributes), f"{name}: attribute count mismatch"
    assert parsed['n_out'] == len(backend.borg_outputs), f"{name}: output count mismatch"
    assert parsed['n_const'] == len(backend.borg_consts), f"{name}: const count mismatch"

    # Verify instructions
    for i, (enc, _) in enumerate(backend.borg_instrs):
        assert parsed['instrs'][i] == enc, f"{name}: instr[{i}] mismatch: 0x{parsed['instrs'][i]:04X} != 0x{enc:04X}"

    # Verify register maps
    assert parsed['uniform_regs'] == [p for _, p in backend.borg_uniforms], f"{name}: uniform regs mismatch"
    assert parsed['attribute_regs'] == [p for _, p in backend.borg_attributes], f"{name}: attribute regs mismatch"
    assert parsed['output_regs'] == [p for _, p in backend.borg_outputs], f"{name}: output regs mismatch"
    assert parsed['const_regs'] == [p for _, p, _ in backend.borg_consts], f"{name}: const regs mismatch"

    # Verify constant values
    for i, (_, _, val) in enumerate(backend.borg_consts):
        expected_fp16 = BorgBackend._float_to_fp16(float(val))
        assert parsed['const_vals'][i] == expected_fp16, \
            f"{name}: const_val[{i}] mismatch: 0x{parsed['const_vals'][i]:04X} != 0x{expected_fp16:04X}"

    print(f"  {name}: OK ({len(blob)} bytes)")


def test_pipeline(name, spvasm_path, expected_instrs, expected_unis,
                  expected_attrs, expected_outs):
    """End-to-end test: SPIR-V text → compiler → backend → verify encodings."""
    from spirv_compiler import TinySpirvCompiler

    # Stage 1: SPIR-V → pseudo-assembly
    compiler = TinySpirvCompiler()
    asm = compiler.compile_file(spvasm_path)
    assert not asm.startswith("Error"), f"{name}: compile failed: {asm}"

    # Stage 2: pseudo-assembly → backend
    backend = BorgBackend()
    backend.lower(asm)

    # Verify instruction encodings
    actual_instrs = [enc for enc, _ in backend.borg_instrs]
    assert actual_instrs == expected_instrs, \
        f"{name}: instrs mismatch\n  expected: {['0x%04X' % x for x in expected_instrs]}\n  actual:   {['0x%04X' % x for x in actual_instrs]}"

    # Verify register allocations (physical register indices)
    actual_unis = [p for _, p in backend.borg_uniforms]
    actual_attrs = [p for _, p in backend.borg_attributes]
    actual_outs = [p for _, p in backend.borg_outputs]
    assert actual_unis == expected_unis, f"{name}: uniform regs {actual_unis} != {expected_unis}"
    assert actual_attrs == expected_attrs, f"{name}: attribute regs {actual_attrs} != {expected_attrs}"
    assert actual_outs == expected_outs, f"{name}: output regs {actual_outs} != {expected_outs}"

    # Also verify round-trip
    blob = backend.emit_binary()
    parsed = parse_spirb(blob)
    assert parsed['instrs'] == expected_instrs, f"{name}: binary round-trip instrs mismatch"

    print(f"  {name}: OK (pipeline → {len(backend.borg_instrs)} instrs, {len(blob)} bytes)")


if __name__ == '__main__':
    test_dir = os.path.dirname(os.path.abspath(__file__))

    print("SPIR-B round-trip tests:")
    test_shader("vert", os.path.join(test_dir, "vert.s"))
    test_shader("frag", os.path.join(test_dir, "frag.s"))
    test_shader("rasterize", os.path.join(test_dir, "rasterize.s"))

    print("End-to-end pipeline tests:")
    # Known-good instruction encodings from current shaders
    if os.path.exists(os.path.join(test_dir, "vert.spvasm")):
        test_pipeline("vert", os.path.join(test_dir, "vert.spvasm"),
                      expected_instrs=[0x4530, 0x8640, 0x4521, 0x9631],
                      expected_unis=[2, 3, 4],
                      expected_attrs=[5, 6],
                      expected_outs=[0, 1])
    if os.path.exists(os.path.join(test_dir, "frag.spvasm")):
        test_pipeline("frag", os.path.join(test_dir, "frag.spvasm"),
                      expected_instrs=[0x4218, 0x4239, 0x424A, 0x4580, 0x8690, 0x87A0],
                      expected_unis=[2, 5, 6, 7],
                      expected_attrs=[1, 3, 4],
                      expected_outs=[0])

    print("All tests passed!")
