import json
import numpy as np

with open('../../data/test_cases.json', 'r') as f:
    data = json.load(f)

def float_to_hex16(f):
    return hex(np.array([f], dtype=np.float16).view(np.uint16)[0])

def fp16_to_str(f):
    """Convert float to a short decimal string via numpy float16."""
    v = float(np.float16(f))
    if v == 0.0:
        return "0.0"
    if v == int(v):
        return f"{int(v)}.0"
    return f"{v:.4f}".rstrip('0')

with open('test_data.h', 'w') as f:
    f.write("// Auto-generated from test_cases.json using FP16 precision\n")
    f.write(f"#define NUM_TESTS {len(data['pairs'])}\n")
    f.write("const unsigned int test_pairs_i[NUM_TESTS][3] = {\n")
    for a, b in data['pairs']:
        a16 = np.float16(a)
        b16 = np.float16(b)
        expected = np.float16(a16 + b16)
        f.write(f"    {{ {float_to_hex16(a16)}, {float_to_hex16(b16)}, {float_to_hex16(expected)} }},\n")
    f.write("};\n\n")

    # Pre-computed description strings for each test
    f.write("// Pre-computed FP16 descriptions (a_str, b_str, expected_str)\n")
    f.write("static char *test_desc[NUM_TESTS][3] = {\n")
    for a, b in data['pairs']:
        a16 = np.float16(a)
        b16 = np.float16(b)
        expected = np.float16(a16 + b16)
        f.write(f'    {{ "{fp16_to_str(a)}", "{fp16_to_str(b)}", "{fp16_to_str(float(a16)+float(b16))}" }},\n')
    f.write("};\n")
