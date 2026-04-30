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

sys.path.append(os.path.join(os.path.dirname(__file__), '../software/borg/python/build'))
sys.path.append(os.path.join(os.path.dirname(__file__), '../fpga/common/host'))

import borg_utils_c
fp16_to_float = borg_utils_c.fp16_to_float

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
            # 8 bytes per pixel: lo (uint32) + hi (uint32)
            row_data = fb.read(w * 8)
            if not row_data:
                break
            for x in range(w):
                lo = struct.unpack_from('<I', row_data, x * 8 + 0)[0]  # {B[31:16], Z[15:0]}
                hi = struct.unpack_from('<I', row_data, x * 8 + 4)[0]  # {R[31:16], G[15:0]}
                r_fp16 = (hi >> 16) & 0xFFFF
                g_fp16 = hi & 0xFFFF
                b_fp16 = (lo >> 16) & 0xFFFF
                
                fp.write(f"{fp16_to_byte(r_fp16)} {fp16_to_byte(g_fp16)} {fp16_to_byte(b_fp16)} ")
            fp.write("\n")

if __name__ == '__main__':
    if len(sys.argv) != 5:
        print("Usage: python postprocess.py <input.bin> <output.ppm> <width> <height>")
        sys.exit(1)
        
    postprocess(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
