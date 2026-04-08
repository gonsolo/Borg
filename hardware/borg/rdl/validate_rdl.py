#!/usr/bin/env python3
"""Validate borg_gpu.rdl against systemrdl-compiler and print the register map."""

import sys
import systemrdl

rdlc = systemrdl.RDLCompiler()

try:
    rdlc.compile_file("hardware/borg/rdl/borg_gpu.rdl")
    root = rdlc.elaborate()
except systemrdl.RDLCompileError as e:
    print(f"COMPILE ERROR: {e}", file=sys.stderr)
    sys.exit(1)

print("✓ borg_gpu.rdl compiled and elaborated successfully.\n")
print(f"{'Register':<30} {'Offset':>8}  {'Size':>6}")
print("-" * 50)

def walk(node, depth=0):
    for child in node.children():
        if isinstance(child, systemrdl.node.RegNode):
            if child.is_array:
                dim = child.array_dimensions[0]
                stride = child.array_stride
                base = child.raw_address_offset
                name = f"{child.inst_name}[0..{dim-1}]"
                end = base + (dim - 1) * stride
                print(f"  {name:<28} 0x{base:03X}–0x{end:03X}  {child.size:>3}B × {dim}")
            else:
                print(f"  {child.inst_name:<28} 0x{child.address_offset:03X}    {child.size:>3}B")
            
            # Print fields
            for field in child.fields():
                sw = field.get_property('sw')
                hw = field.get_property('hw')
                print(f"    .{field.inst_name:<24} [{field.high:>2}:{field.low:<2}]  sw={sw} hw={hw}")
        elif isinstance(child, (systemrdl.node.AddrmapNode, systemrdl.node.RegfileNode)):
            print(f"\n  [{child.inst_name}]")
            walk(child, depth+1)

walk(root)
for child in root.children():
    if isinstance(child, systemrdl.node.AddrmapNode):
        print(f"\nTotal address map size: {child.size} bytes")
