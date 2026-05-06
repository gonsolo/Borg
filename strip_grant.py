import re

with open('docs/A0_roadmap.md', 'r') as f:
    content = f.read()

# 1. Remove Grant tags from headers
content = re.sub(r' \(Grant Task \d(?:-\d)?\)', '', content)
content = re.sub(r' \[Grant Task \d(?: - [^\]]*)?\]', '', content)

# 2. Fix the Step numbering. We have two Step 32s. The VGA one is first.
# We will bump the Fidelity ones (which are after VGA) by 1.
# And then bump the rest of the steps up to 48 by 1.

# Let's just do a simple string replacement in reverse order so we don't double-bump.
for i in range(48, 31, -1):
    old_str = f"### Step {i}:"
    new_str = f"### Step {i+1}:"
    # Only replace occurrences that are *after* the VGA section to avoid hitting VGA twice if it's 32.
    # Actually, the VGA one is Step 32, and the interpolation is also Step 32.
    # Let's specifically target the interpolation one and bump it.
    if i == 32:
        content = content.replace('### Step 32: Fragment Interpolation', '### Step 33: Fragment Interpolation')
    else:
        content = content.replace(old_str, new_str)

with open('docs/A0_roadmap.md', 'w') as f:
    f.write(content)

print("Grant tags stripped and numbering fixed!")
