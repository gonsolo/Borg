#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""Pre-encode FP16 RGB texture dat files into Morton-ordered SPI binaries.

Reads software/borg/*.dat files, outputs *_morton.bin in Morton order,
packing each texel into the 2-word SPI format expected by the FPGA.
This moves all the heavy processing off the RP2040 onto the workstation.
"""

import sys
import os
import struct

sys.path.append(os.path.join(os.path.dirname(__file__), '../software/borg/python/build'))
sys.path.append(os.path.join(os.path.dirname(__file__), '../fpga/host'))

import borg_utils_c
morton_encode = borg_utils_c.morton_encode


def encode_texture(src_path, dst_path):
    tex_size = os.stat(src_path).st_size
    tex_n_texels = tex_size // 6  # 3 channels * 2 bytes
    
    tex_dim = 1
    while tex_dim * tex_dim < tex_n_texels:
        tex_dim *= 2
        
    print(f"Encoding {tex_dim}x{tex_dim} texture: {src_path} -> {dst_path}")
    
    total_words = tex_dim * tex_dim * 2
    out_data = bytearray(total_words * 4) # 4 bytes per word

    with open(src_path, 'rb') as f:
        for y in range(tex_dim):
            row_data = f.read(tex_dim * 6)
            for x in range(tex_dim):
                dst_idx = morton_encode(x, y)
                
                # Fetch RGB from row buffer
                r = struct.unpack_from('<H', row_data, (x * 3 + 0) * 2)[0]
                g = struct.unpack_from('<H', row_data, (x * 3 + 1) * 2)[0]
                b = struct.unpack_from('<H', row_data, (x * 3 + 2) * 2)[0]

                # Pack into 2-word format: Word 0 = {G, R}, Word 1 = {0, B}
                word0 = (g << 16) | r
                word1 = b
                
                struct.pack_into('<I', out_data, (dst_idx * 2 + 0) * 4, word0)
                struct.pack_into('<I', out_data, (dst_idx * 2 + 1) * 4, word1)
                
    with open(dst_path, 'wb') as f:
        f.write(out_data)
        
    print(f"Encoded {total_words * 4} bytes.")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python encode_texture.py <input.dat> <output_morton.bin>")
        sys.exit(1)
        
    encode_texture(sys.argv[1], sys.argv[2])
