#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

"""Convert borg.ppm (256×256 RGB8) to firmware/borg_texture.dat (32×32 FP16 RGB).

Output format: 32×32 pixels, each pixel = 3 × uint16 little-endian (R, G, B as
IEEE 754 half-precision floats in [0..1]).  Linear row-major order — Morton
reordering is handled by the host upload script (render.py).
"""

import sys
import os
import struct
from PIL import Image

sys.path.append(os.path.join(os.path.dirname(__file__), 'host'))
from borg_utils import float_to_fp16


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
