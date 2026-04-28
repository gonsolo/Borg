#!/usr/bin/env python3
import os
import sys

def main():
    # Read the directly collected ASIC files from both stages
    sv_files = set()
    
    for asic_file_list in ['out/hardware/borg/verilog/asic_files.txt']:
        if not os.path.exists(asic_file_list):
            print(f"Error: {asic_file_list} not found. Run 'mill hardware.borg.runMain borg.Main' first.", file=sys.stderr)
            sys.exit(1)
        with open(asic_file_list, 'r') as f:
            for line in f:
                line = line.strip()
                if line:
                    sv_files.add(line)

    if not sv_files:
        print("Error: asic_files.txt is empty — no source files found.", file=sys.stderr)
        sys.exit(1)

    sv_files = sorted(list(sv_files))

    # Verify all listed files actually exist on disk
    # Paths in asic_files.txt are relative to src/ (e.g. ../out/hardware/...)
    missing = [f for f in sv_files if not os.path.exists(os.path.normpath(os.path.join('src', f)))]
    if missing:
        print(f"Error: {len(missing)} generated file(s) not found:", file=sys.stderr)
        for f in missing:
            print(f"  {f}", file=sys.stderr)
        sys.exit(1)

    yaml_lines = []
    for f in sv_files:
        yaml_lines.append(f'    - "{f}"')
    
    file_list_str = '\n'.join(yaml_lines)
    
    template_path = 'info.template.yaml'
    yaml_path = 'info.yaml'
    
    if not os.path.exists(template_path):
        print(f"Error: {template_path} not found.", file=sys.stderr)
        sys.exit(1)

    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--pdk', default=os.environ.get('PDK', 'sky130A'))
    args = parser.parse_args()

    tiles = "4x4" if args.pdk == "gf180mcuD" else "8x4"

    with open(template_path, 'r') as f:
        content = f.read()
    
    # Replace the template markers
    new_content = content.replace('{{source_files}}', file_list_str)
    new_content = new_content.replace('{{tiles}}', f'"{tiles}"')
    
    with open(yaml_path, 'w') as f:
        f.write("# This file is generated from info.template.yaml — do not edit manually!\n")
        f.write(new_content)

    print(f"Generated {yaml_path} from {template_path} with {len(sv_files)} ASIC source files.")

if __name__ == '__main__':
    main()
