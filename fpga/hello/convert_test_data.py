import json
import sys
import numpy as np

with open('../../data/test_cases.json', 'r') as f:
    data = json.load(f)

def float_to_hex16(f):
    return hex(np.array([f], dtype=np.float16).view(np.uint16)[0])

if '--reference' in sys.argv:
    # Print reference table so the user can compare against FPGA hex output
    print("FP16 Test Reference Table:")
    print("  Hex values are IEEE 754 half-precision bit patterns (numpy float16).")
    print("  The FPGA computes a + b and reports the result as raw hex.")
    print(f"{'Hex A':>8} {'Hex B':>8} {'Hex Exp':>8}   {'A':>10} {'B':>10} {'Expected':>10}")
    print("-" * 64)
    for a, b in data['pairs']:
        a16 = np.float16(a)
        b16 = np.float16(b)
        expected = np.float16(a16 + b16)
        ha = f"0x{np.array([a16], dtype=np.float16).view(np.uint16)[0]:04x}"
        hb = f"0x{np.array([b16], dtype=np.float16).view(np.uint16)[0]:04x}"
        he = f"0x{np.array([expected], dtype=np.float16).view(np.uint16)[0]:04x}"
        print(f"{ha:>8} {hb:>8} {he:>8}   {float(a16):>10g} {float(b16):>10g} {float(expected):>10g}")
else:
    # Generate test_data.h
    with open('test_data.h', 'w') as f:
        f.write("// Auto-generated from test_cases.json using FP16 precision\n")
        f.write(f"#define NUM_TESTS {len(data['pairs'])}\n")
        # test_pairs_i: each row is {a, b, expected} as IEEE 754 half-precision
        # bit patterns stored in unsigned ints, where expected = fp16(a + b).
        f.write("const unsigned int test_pairs_i[NUM_TESTS][3] = {\n")
        for a, b in data['pairs']:
            a16 = np.float16(a)
            b16 = np.float16(b)
            expected = np.float16(a16 + b16)
            f.write(f"    {{ {float_to_hex16(a16)}, {float_to_hex16(b16)}, {float_to_hex16(expected)} }},\n")
        f.write("};\n")

