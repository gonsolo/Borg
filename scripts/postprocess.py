#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""Convert FP16 raw binary framebuffers from the FPGA into PPM format.

Reads *_00.bin, writes *_00.ppm.
Uses the workstation's C bindings for blazing fast FP16 -> Float decoding.
"""

import sys
import os
import struct

sys.path.append(os.path.join(os.path.dirname(__file__), '../simulation/verilator/build/common_build'))
sys.path.append(os.path.join(os.path.dirname(__file__), '../simulation/arcilator/build/common_build'))
sys.path.append(os.path.join(os.path.dirname(__file__), '../fpga/host'))

try:
    import borg_utils_c
    fp16_to_float = borg_utils_c.fp16_to_float
except ImportError:
    import borg_utils
    fp16_to_float = borg_utils.fp16_to_float

def fp16_to_byte(bits):
    if not bits:
        return 0
    v = fp16_to_float(bits)
    if v != v or v == float('inf') or v == float('-inf'): # isnan/isinf
        return 0
    return max(0, min(255, int(v * 255 + 0.5)))

def postprocess(bin_file, ppm_file, w, h):
    print(f"Postprocessing {w}x{h} framebuffer: {bin_file} -> {ppm_file}")
    
    with open(bin_file, 'rb') as fb, open(ppm_file, 'w') as fp:
        fp.write(f"P3\n{w} {h}\n255\n")
        
        for y in range(h):
            row_data = fb.read(w * 6)
            if not row_data:
                break
            for x in range(w):
                r_fp16 = struct.unpack_from('<H', row_data, x * 6 + 0)[0]
                g_fp16 = struct.unpack_from('<H', row_data, x * 6 + 2)[0]
                b_fp16 = struct.unpack_from('<H', row_data, x * 6 + 4)[0]
                
                fp.write(f"{fp16_to_byte(r_fp16)} {fp16_to_byte(g_fp16)} {fp16_to_byte(b_fp16)} ")
            fp.write("\n")

if __name__ == '__main__':
    if len(sys.argv) != 5:
        print("Usage: python postprocess.py <input.bin> <output.ppm> <width> <height>")
        sys.exit(1)
        
    postprocess(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
