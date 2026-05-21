#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# UART framebuffer receiver — captures one tiled FP16 frame from the ULX3S
# triangle firmware over /dev/ttyUSB0 and saves it as both raw bytes and a
# PNG image.
#
# Wire format (matches dump_fb_uart in software/borg/borg_triangle.c):
#   "FBFB"                — 4-byte sync magic
#   width  (u16 LE)
#   height (u16 LE)
#   pixels (W*H*8 bytes)  — tiled, 8 bytes/pixel: R G B Z each FP16 LE
#   "ENDB"                — 4-byte trailer magic
#
# Tile layout (matches HdmiScanoutFp16):
#   tile_col   = x >> 2
#   tile_row   = y >> 2
#   tile_index = tile_row * (W/4) + tile_col
#   pix_index  = (y & 3) * 4 + (x & 3)
#   byte_addr  = tile_index * 128 + pix_index * 8
#
# Pixel bytes within a tile:
#   +0..+1  R (FP16 LE)
#   +2..+3  G (FP16 LE)
#   +4..+5  B (FP16 LE)
#   +6..+7  Z (FP16 LE)
#
# Usage:
#   ./uart_fb_receiver.py [-p /dev/ttyUSB0] [-o frame] [-b 115200]
#
# Output files:
#   frame.bin   raw framebuffer bytes
#   frame.png   detiled RGB8 (FP16→RGB8 conversion matches the scanout)

import argparse
import struct
import sys

import serial

MAGIC_START = b"FBFB"
MAGIC_END   = b"ENDB"


def fp16_to_unit(u16):
    """FP16 → float in [0..1] roughly.  Match HdmiScanoutFp16 conversion intent."""
    sign = (u16 >> 15) & 1
    exp  = (u16 >> 10) & 0x1F
    mant = u16 & 0x3FF
    if sign or exp == 0:
        return 0.0
    if exp == 0x1F:   # inf / NaN — saturate
        return 1.0
    # Normal FP16: (1 + mant/1024) * 2^(exp-15)
    f = (1.0 + mant / 1024.0) * (2.0 ** (exp - 15))
    if f >= 1.0:
        return 1.0
    if f < 0.0:
        return 0.0
    return f


def detile(buf, w, h):
    """Return W*H array of (r,g,b,z) tuples in raster (x,y) order."""
    out = [None] * (w * h)
    for y in range(h):
        for x in range(w):
            tile_col = x >> 2
            tile_row = y >> 2
            tile_idx = tile_row * (w // 4) + tile_col
            pix_idx  = (y & 3) * 4 + (x & 3)
            off      = tile_idx * 128 + pix_idx * 8
            r = struct.unpack_from("<H", buf, off + 0)[0]
            g = struct.unpack_from("<H", buf, off + 2)[0]
            b = struct.unpack_from("<H", buf, off + 4)[0]
            z = struct.unpack_from("<H", buf, off + 6)[0]
            out[y * w + x] = (r, g, b, z)
    return out


def hunt_magic(ser, magic, deadline=None):
    """Read bytes until `magic` has been observed.  Returns when found."""
    window = b""
    while True:
        b = ser.read(1)
        if not b:
            sys.stderr.write(".")
            sys.stderr.flush()
            continue
        window = (window + b)[-len(magic):]
        if window == magic:
            return


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-p", "--port", default="/dev/ttyUSB0")
    ap.add_argument("-b", "--baud", type=int, default=115200)
    ap.add_argument("-o", "--out",  default="frame")
    ap.add_argument("-t", "--timeout", type=float, default=2.0,
                    help="per-read timeout, seconds")
    args = ap.parse_args()

    print(f"Opening {args.port} @ {args.baud} baud …", flush=True)
    ser = serial.Serial(args.port, args.baud, timeout=args.timeout)

    print("Waiting for FBFB sync magic …", flush=True)
    hunt_magic(ser, MAGIC_START)
    print(" got it.")

    hdr = ser.read(4)
    if len(hdr) != 4:
        sys.exit("Timed out reading width/height")
    w, h = struct.unpack("<HH", hdr)
    print(f"Frame {w}×{h} → {w*h*8} bytes of pixels …", flush=True)

    expected = w * h * 8
    pix = b""
    while len(pix) < expected:
        chunk = ser.read(expected - len(pix))
        if not chunk:
            sys.stderr.write(f"\n  stalled at {len(pix)}/{expected}\n")
            continue
        pix += chunk
        sys.stderr.write(f"\r  {len(pix)}/{expected}")
        sys.stderr.flush()
    sys.stderr.write("\n")

    trailer = ser.read(4)
    if trailer != MAGIC_END:
        print(f"WARNING: trailer mismatch: {trailer!r} != {MAGIC_END!r}")

    with open(args.out + ".bin", "wb") as f:
        f.write(pix)
    print(f"Wrote {args.out}.bin ({len(pix)} bytes)")

    pixels = detile(pix, w, h)

    try:
        from PIL import Image
    except ImportError:
        print("PIL not installed — skipping PNG.  pip install pillow to enable.")
        return

    img = Image.new("RGB", (w, h))
    for y in range(h):
        for x in range(w):
            r16, g16, b16, _z = pixels[y * w + x]
            r = int(min(255, max(0, fp16_to_unit(r16) * 255)))
            g = int(min(255, max(0, fp16_to_unit(g16) * 255)))
            b = int(min(255, max(0, fp16_to_unit(b16) * 255)))
            img.putpixel((x, y), (r, g, b))

    # Up-scale 8× for easy visual inspection.
    img8x = img.resize((w * 8, h * 8), Image.NEAREST)
    img8x.save(args.out + ".png")
    print(f"Wrote {args.out}.png ({w*8}×{h*8})")


if __name__ == "__main__":
    main()
