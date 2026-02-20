import json

with open('../../borg_peripheral/data/test_cases.json', 'r') as f:
    data = json.load(f)

with open('test_data.h', 'w') as f:
    f.write("// Auto-generated from test_cases.json\n")
    f.write(f"#define NUM_TESTS {len(data['pairs'])}\n")
    f.write("float test_pairs[NUM_TESTS][2] = {\n")
    for a, b in data['pairs']:
        f.write(f"    {{ {a}f, {b}f }},\n")
    f.write("};\n")
