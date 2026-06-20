import argparse
import sys
import os
import systemrdl

parser = argparse.ArgumentParser(description="Validate RDL files.")
parser.add_argument("--verbose", "-v", action="store_true", help="Print full register map")
args = parser.parse_args()

rdl_dir = os.path.dirname(os.path.abspath(__file__))

rdlc = systemrdl.RDLCompiler()

# Compile all component RDL files first, then the top-level SoC map
rdl_files = [
    os.path.join(rdl_dir, "gpio.rdl"),
    os.path.join(rdl_dir, "uart.rdl"),
    os.path.join(rdl_dir, "dram.rdl"),
    os.path.join(rdl_dir, "borg.rdl"),
    os.path.join(rdl_dir, "soc.rdl"),
]

try:
    for f in rdl_files:
        rdlc.compile_file(f)
    root = rdlc.elaborate()
except systemrdl.RDLCompileError as e:
    print(f"COMPILE ERROR: {e}", file=sys.stderr)
    sys.exit(1)

if args.verbose:
    print(f"{'Register':<30} {'Offset':>10}  {'Size':>6}")
    print("-" * 55)

    def walk(node, depth=0):
        indent = "  " * depth
        for child in node.children():
            if isinstance(child, systemrdl.node.RegNode):
                if child.is_array:
                    dim = child.array_dimensions[0]
                    stride = child.array_stride
                    base = child.raw_address_offset
                    name = f"{child.inst_name}[0..{dim-1}]"
                    end = base + (dim - 1) * stride
                    print(f"{indent}  {name:<28} 0x{base:03X}–0x{end:03X}  {child.size:>3}B × {dim}")
                else:
                    print(f"{indent}  {child.inst_name:<28} 0x{child.address_offset:03X}    {child.size:>3}B")
                
                for field in child.fields():
                    sw = field.get_property('sw')
                    hw = field.get_property('hw')
                    print(f"{indent}    .{field.inst_name:<24} [{field.high:>2}:{field.low:<2}]  sw={sw} hw={hw}")
            elif isinstance(child, (systemrdl.node.AddrmapNode, systemrdl.node.RegfileNode)):
                print(f"\n{indent}  [{child.inst_name}] @ 0x{child.address_offset:08X}")
                walk(child, depth+1)

    walk(root)
    for child in root.children():
        if isinstance(child, systemrdl.node.AddrmapNode):
            print(f"\nTotal SoC address map size: {child.size} bytes")

print("✓ RDL validation successful.")
