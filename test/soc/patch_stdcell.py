import re
import sys

def patch_stdcell(input_file, output_file):
    with open(input_file, 'r') as f:
        content = f.read()

    # Remove specify blocks completely
    content = re.sub(r'^\s*specify\b.*?\bendspecify\s*$', '', content, flags=re.MULTILINE | re.DOTALL)
    
    # In ihp_latch and similar UDP instantiations, delayed_ signals might be used
    # But if we just remove the delay prefix, we might connect a wire to itself
    # Actually, the delayed_ signals are defined as wires. Let's just remove the delayed_ prefix
    # AND remove the wires that are generated for delayed_ signals.
    # Wait, the easiest way is to modify the patching.
    # Let's see what delayed_ is. It's usually from `wire delayed_A; assign delayed_A = A;` or similar inside specify.
    # If we remove specify, delayed_A might be undefined.
    # So replacing delayed_A with A is correct, BUT we must ensure we don't create `wire A, A;` or `assign A = A;`
    
    # We will replace `delayed_` with `` across the file.
    content = content.replace('delayed_', '')

    with open(output_file, 'w') as f:
        f.write(content)

if __name__ == '__main__':
    patch_stdcell(sys.argv[1], sys.argv[2])
