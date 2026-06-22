#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Compare a captured ULX3S framebuffer (frame.bin from uart_fb_receiver.py)
# against a sim golden PPM.  Detiles + FP16->RGB8 exactly like the receiver,
# then reports non-black pixel count and per-pixel diffs vs the golden.
#
# Usage: ./compare_fb.py frame.bin ../../simulation/golden/triangle_00.ppm

import struct
import sys


def fp16_to_rgb8(u16):
    sign = (u16 >> 15) & 1
    exp  = (u16 >> 10) & 0x1F
    mant = u16 & 0x3FF
    if sign or exp == 0:
        f = 0.0
    elif exp == 0x1F:
        f = 1.0
    else:
        f = (1.0 + mant / 1024.0) * (2.0 ** (exp - 15))
    f = max(0.0, min(1.0, f))
    return int(min(255, max(0, f * 255)))


def detile_rgb(buf, w, h):
    out = [None] * (w * h)
    for y in range(h):
        for x in range(w):
            tile_idx = (y >> 2) * (w // 4) + (x >> 2)
            pix_idx  = (y & 3) * 4 + (x & 3)
            off      = tile_idx * 128 + pix_idx * 8
            r = fp16_to_rgb8(struct.unpack_from("<H", buf, off + 0)[0])
            g = fp16_to_rgb8(struct.unpack_from("<H", buf, off + 2)[0])
            b = fp16_to_rgb8(struct.unpack_from("<H", buf, off + 4)[0])
            out[y * w + x] = (r, g, b)
    return out


def read_ppm(path):
    vals = open(path).read().split()
    assert vals[0] == "P3", "only P3 PPM supported"
    w, h = int(vals[1]), int(vals[2])
    px = vals[4:]
    out = [(int(px[i]), int(px[i + 1]), int(px[i + 2])) for i in range(0, len(px), 3)]
    return w, h, out


def main():
    binp, goldp = sys.argv[1], sys.argv[2]
    gw, gh, gold = read_ppm(goldp)
    buf = open(binp, "rb").read()
    cap = detile_rgb(buf, gw, gh)

    cap_nz  = sum(1 for p in cap if p != (0, 0, 0))
    gold_nz = sum(1 for p in gold if p != (0, 0, 0))

    # "shape diff": pixels where black/non-black status disagrees.
    shape_diff = sum(1 for c, g in zip(cap, gold) if (c != (0, 0, 0)) != (g != (0, 0, 0)))
    exact_diff = sum(1 for c, g in zip(cap, gold) if c != g)

    print(f"capture non-black: {cap_nz}")
    print(f"golden  non-black: {gold_nz}")
    print(f"shape diff (black/non-black mismatch): {shape_diff} px")
    print(f"exact diff (rgb mismatch):             {exact_diff} px of {gw*gh}")

    if shape_diff == 0:
        print("RESULT: PASS (shape-exact match to golden)")
        return 0
    if cap_nz == 0:
        print("RESULT: FAIL (empty render — nothing drawn)")
        return 2
    print("RESULT: PARTIAL (geometry present but shape differs)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
