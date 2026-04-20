#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""Convert borg.ppm (256×256 RGB8) to firmware/borg_texture.dat (256×256 FP16 RGB).

Output format: 256×256 pixels, each pixel = 3 × uint16 little-endian (R, G, B as
IEEE 754 half-precision floats in [0..1]).  Linear row-major order — Morton
reordering is handled by the host upload script (render.py).
"""

import sys
import os
import struct
from PIL import Image

sys.path.append(os.path.join(os.path.dirname(__file__), 'host'))
from borg_utils import float_to_fp16


def generate_texture(size, filename):
    if not os.path.exists('borg.ppm'):
        if os.path.exists(f'../software/borg/{filename}'):
            print(f"Skipping {filename} (source borg.ppm missing, but .dat exists)")
            return
        print(f"WARNING: borg.ppm not found, generating fallback pattern for {filename}")
        img = Image.new('RGB', (size, size), color=(20, 20, 20))
        # Draw a simple 'B' for Borg
        from PIL import ImageDraw
        draw = ImageDraw.Draw(img)
        draw.text((size//4, size//4), "B", fill=(0, 255, 0))
    else:
        img = Image.open('borg.ppm').convert('RGB').resize((size, size), Image.LANCZOS)
    with open(f'../software/borg/{filename}', 'wb') as f:
        for y in range(size):
            for x in range(size):
                r8, g8, b8 = img.getpixel((x, y))
                r = float_to_fp16(r8 / 255.0)
                g = float_to_fp16(g8 / 255.0)
                b = float_to_fp16(b8 / 255.0)
                f.write(struct.pack('<HHH', r, g, b))
    print(f"Generated ../software/borg/{filename} ({size}x{size}, {size*size*6} bytes)")


def generate_small_from_large(src_dat, dst_dat, src_dim, dst_dim):
    """Box-filter downsample a .dat texture (FP16 RGB) from src_dim to dst_dim.
    Used when borg.ppm is missing but the large .dat already exists — avoids
    the fallback dark-gray-plus-green-B pattern that causes 'black cube' renders.
    """
    def fp16_to_float(h):
        s = (h >> 15) & 1
        e = (h >> 10) & 0x1f
        m = h & 0x3ff
        if e == 0:
            v = m / 1024.0 * 2**(-14)
        elif e == 31:
            v = float('inf') if m == 0 else float('nan')
        else:
            v = (1 + m / 1024.0) * 2**(e - 15)
        return -v if s else v

    scale = src_dim // dst_dim
    with open(src_dat, 'rb') as f:
        src = f.read()

    out = bytearray()
    for dy in range(dst_dim):
        for dx in range(dst_dim):
            rsum = gsum = bsum = 0.0
            for sy in range(scale):
                for sx in range(scale):
                    px = (dy * scale + sy) * src_dim + (dx * scale + sx)
                    r, g, b = struct.unpack_from('<HHH', src, px * 6)
                    rsum += fp16_to_float(r)
                    gsum += fp16_to_float(g)
                    bsum += fp16_to_float(b)
            n = scale * scale
            out += struct.pack('<HHH',
                               float_to_fp16(rsum / n),
                               float_to_fp16(gsum / n),
                               float_to_fp16(bsum / n))

    with open(dst_dat, 'wb') as f:
        f.write(out)
    print(f"Generated {dst_dat} ({dst_dim}x{dst_dim}, {len(out)} bytes) "
          f"[box-filtered from {src_dim}x{src_dim} .dat]")


# --- Main ---

# Always generate the full-resolution texture first (from borg.ppm if available)
generate_texture(256, 'borg_texture.dat')

# Generate 64x64 small texture.
# Preference order:
#   1. Resize from borg.ppm (best quality, same code path as large)
#   2. Box-filter from the large .dat (correct content, no borg.ppm needed)
#   3. Fallback dark pattern (last resort, will look wrong — avoid if possible)
LARGE_DAT = '../software/borg/borg_texture.dat'
SMALL_DAT = '../software/borg/borg_texture_small.dat'
if os.path.exists('borg.ppm'):
    generate_texture(64, 'borg_texture_small.dat')
elif os.path.exists(LARGE_DAT):
    print("borg.ppm missing — deriving 64x64 texture from 256x256 .dat (box filter)")
    generate_small_from_large(LARGE_DAT, SMALL_DAT, 256, 64)
else:
    # True last resort: no source at all
    generate_texture(64, 'borg_texture_small.dat')
