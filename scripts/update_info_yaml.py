#!/usr/bin/env python3
import os

def main():
    # Read the directly collected ASIC files from both stages
    sv_files = set()
    
    for asic_file_list in ['out/hardware/borg/verilog/asic_files.txt']:
        if os.path.exists(asic_file_list):
            with open(asic_file_list, 'r') as f:
                for line in f:
                    line = line.strip()
                    if line:
                        sv_files.add(line)

    sv_files = sorted(list(sv_files))

    yaml_lines = []
    for f in sv_files:
        yaml_lines.append(f'    - "{f}"')
    
    file_list_str = '\n'.join(yaml_lines)
    
    template_path = 'info.template.yaml'
    yaml_path = 'info.yaml'
    
    if not os.path.exists(template_path):
        print(f"Error: {template_path} not found.")
        return

    with open(template_path, 'r') as f:
        content = f.read()
    
    # Replace the template marker with the structured list
    new_content = content.replace('{{source_files}}', file_list_str)
    
    with open(yaml_path, 'w') as f:
        f.write("# This file is generated from info.template.yaml — do not edit manually!\n")
        f.write(new_content)

    print(f"Generated {yaml_path} from {template_path} with {len(sv_files)} ASIC source files.")

if __name__ == '__main__':
    main()
