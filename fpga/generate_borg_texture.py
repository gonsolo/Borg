#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""Convert borg.ppm (256×256 RGB8) to firmware/borg_texture.dat (32×32 FP16 RGB).

Output format: 32×32 pixels, each pixel = 3 × uint16 little-endian (R, G, B as
IEEE 754 half-precision floats in [0..1]).  Linear row-major order — Morton
reordering is handled by the host upload script (render.py).
"""

import struct
from PIL import Image

# FP16 conversion (matches host/fp16_utils.py)
def float_to_fp16(f):
    if f == 0.0:
        return 0x0000
    sign = 0
    if f < 0:
        sign = 1
        f = -f
    if f >= 65504.0:
        return (sign << 15) | 0x7C00
    if f < 2.0**-24:
        return sign << 15
    if f < 2.0**-14:
        return (sign << 15) | int(f / 2.0**-14 * 1024 + 0.5)
    exp = 0
    tmp = f
    while tmp >= 2.0:
        tmp /= 2.0
        exp += 1
    while tmp < 1.0:
        tmp *= 2.0
        exp -= 1
    frac_bits = int((tmp - 1.0) * 1024 + 0.5)
    biased = exp + 15
    if frac_bits >= 1024:
        frac_bits = 0
        biased += 1
    if biased >= 31:
        return (sign << 15) | 0x7C00
    return (sign << 15) | (biased << 10) | frac_bits


TEX_SIZE = 32

img = Image.open('borg.ppm').convert('RGB').resize((TEX_SIZE, TEX_SIZE), Image.LANCZOS)

with open('../software/borg/borg_texture.dat', 'wb') as f:
    for y in range(TEX_SIZE):
        for x in range(TEX_SIZE):
            r8, g8, b8 = img.getpixel((x, y))
            r = float_to_fp16(r8 / 255.0)
            g = float_to_fp16(g8 / 255.0)
            b = float_to_fp16(b8 / 255.0)
            f.write(struct.pack('<HHH', r, g, b))

print(f"Generated ../software/borg/borg_texture.dat ({TEX_SIZE}x{TEX_SIZE}, 6144 bytes)")
