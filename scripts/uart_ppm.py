#!/usr/bin/env python3
"""Turn a UART capture of borg_replay.c (FRAME-BEGIN w h fmt, hex words, FRAME-END) into a PPM.

usage: uart_ppm.py capture.log out.ppm
       uart_ppm.py --raw dump.bin W H FMT out.ppm
"""
import re
import sys

if sys.argv[1] == "--raw":   # --raw dump.bin W H FMT out.ppm: little-endian words from a simulator SDRAM dump
    import struct
    raw = open(sys.argv[2], "rb").read()
    w, h, fmt = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
    words = list(struct.unpack("<%dI" % (len(raw) // 4), raw))
    sys.argv[2] = sys.argv[6]
else:
    text = open(sys.argv[1], errors="replace").read()
    m = re.search(r"FRAME-BEGIN ([0-9a-f]{8}) ([0-9a-f]{8}) ([0-9a-f]{8})(.*?)FRAME-END", text, re.S)
    if not m:
        sys.exit("no complete frame in capture")
    w, h, fmt = (int(m.group(i), 16) for i in (1, 2, 3))
    digits = re.sub(r"[^0-9a-f]", "", m.group(4))
    words = [int(digits[i:i + 8], 16) for i in range(0, len(digits) - 7, 8)]
need = w * h * (4 if fmt else 2) // 4
if len(words) != need:
    sys.exit(f"expected {need} words, got {len(words)}")

rgb = bytearray(w * h * 3)
for y in range(h):
    for x in range(w):
        tile = (y >> 2) * (w >> 2) + (x >> 2)
        ti = (x & 3) | ((y & 3) << 2)
        if fmt:
            px = words[tile * 16 + ti]
            b0, b1, b2 = px & 255, (px >> 8) & 255, (px >> 16) & 255
            r, g, b = (b0, b1, b2) if fmt == 1 else (b2, b1, b0)
        else:
            word = words[tile * 8 + (ti >> 1)]
            px = (word >> 16) & 0xFFFF if ti & 1 else word & 0xFFFF
            r, g, b = ((px >> 11) & 31) << 3, ((px >> 5) & 63) << 2, (px & 31) << 3
            r |= r >> 5; g |= g >> 6; b |= b >> 5
        i = (y * w + x) * 3
        rgb[i:i + 3] = bytes((r, g, b))
open(sys.argv[2], "wb").write(f"P6\n{w} {h}\n255\n".encode() + rgb)
