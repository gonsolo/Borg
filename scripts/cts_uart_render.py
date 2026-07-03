#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""
cts_uart_render.py — Replay a borgvk UART stream and render via sim.

borg_kernel.c (the current firmware) bakes in no geometry/shaders/texture —
everything arrives from borgvk at runtime (0xAD MVP / 0xAE geometry / 0xAF
texture / 0xB0 shaders), so rendering anything requires replaying a full
captured burst, not just an MVP packet.

Usage:
    python3 scripts/cts_uart_render.py <sim_bin> <firmware.bin> <out.ppm> [W] [H] [--capture FILE]

--capture FILE   Replay a pre-captured borgvk UART burst (0xAE/0xAF/0xB0/0xAD
                 packets) instead of synthesizing a bare MVP packet. Required
                 for kernel.bin, which renders nothing on MVP alone. A real
                 burst is tens of KB and needs ~2170 sim-cycles/byte just for
                 the wire transfer (CTS_MAX_CYCLES is bumped accordingly here).
                 See simulation/golden/borgvk_capture.bin.

Without --capture, a bare 0xAD (MVP) packet is sent using the firmware's
default camera angles (rx=30°, ry=45°) — only useful against firmware with
baked shaders/geometry (the old vkcube.bin; not the current kernel.bin).

The sim binary must support --cts-uart (both arcilator_sim and verilator_sim do).
"""

import math, os, struct, subprocess, sys, tempfile


# ---------------------------------------------------------------------------
# Column-major 4×4 float matrix helpers matching borg_vkcube.c
# ---------------------------------------------------------------------------

def _zeros():
    return [0.0] * 16

def mat4_rotate_x(r):
    m = _zeros()
    c, s = math.cos(r), math.sin(r)
    m[0]  = 1.0   # col0[0]
    m[5]  = c     # col1[1]
    m[6]  = s     # col1[2]
    m[9]  = -s    # col2[1]
    m[10] = c     # col2[2]
    m[15] = 1.0   # col3[3]
    return m

def mat4_rotate_y(r):
    m = _zeros()
    c, s = math.cos(r), math.sin(r)
    m[0]  = c     # col0[0]
    m[2]  = -s    # col0[2]
    m[5]  = 1.0   # col1[1]
    m[8]  = s     # col2[0]
    m[10] = c     # col2[2]
    m[15] = 1.0   # col3[3]
    return m

def mat4_mul(a, b):
    out = _zeros()
    for c in range(4):
        for r in range(4):
            out[c*4+r] = sum(a[k*4+r] * b[c*4+k] for k in range(4))
    return out

def mat4_mul_ts(sxy, sz, tz, m):
    """Apply the firmware's ts = scale(sxy,sxy,sz) + translate_z(tz) to m."""
    out = _zeros()
    for c in range(4):
        out[c*4+0] = sxy * m[c*4+0]
        out[c*4+1] = sxy * m[c*4+1]
        out[c*4+2] = sz  * m[c*4+2] + tz * m[c*4+3]
        out[c*4+3] = m[c*4+3]
    return out


# ---------------------------------------------------------------------------
# Firmware default angles (borg_vkcube.c: rx_f=0.5236, ry_f=0.7854)
# ts: scale(0.5,0.5,0.25), tz=0.5
# ---------------------------------------------------------------------------

def build_mvp():
    rx = mat4_rotate_x(0.5236)
    ry = mat4_rotate_y(0.7854)
    t1 = mat4_mul(rx, ry)
    return mat4_mul_ts(0.5, 0.25, 0.5, t1)


# ---------------------------------------------------------------------------
# 0xAD packet: marker + 16×float32 LE + XOR checksum
# ---------------------------------------------------------------------------

def build_0xad(mvp):
    pkt = bytearray(66)
    pkt[0] = 0xAD
    for i, f in enumerate(mvp):
        struct.pack_into('<f', pkt, 1 + i * 4, f)
    csum = 0
    for b in pkt[1:65]:
        csum ^= b
    pkt[65] = csum
    return bytes(pkt)


# ---------------------------------------------------------------------------
# RGB888 flat buffer → P3 PPM string
# ---------------------------------------------------------------------------

def rgb888_to_p3(rgb, w, h):
    lines = [f'P3\n{w} {h}\n255']
    row_tokens = []
    for y in range(h):
        row = []
        for x in range(w):
            off = (y * w + x) * 3
            row.extend([str(rgb[off]), str(rgb[off+1]), str(rgb[off+2])])
        row_tokens.append(' '.join(row))
    lines.extend(row_tokens)
    return '\n'.join(lines) + '\n'


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    args = sys.argv[1:]
    capture_path = None
    if '--capture' in args:
        i = args.index('--capture')
        capture_path = args[i + 1]
        del args[i:i + 2]

    if len(args) < 3:
        print('Usage: cts_uart_render.py <sim_bin> <firmware.bin> <out.ppm> [W] [H] [--capture FILE]',
              file=sys.stderr)
        sys.exit(1)

    sim_bin  = args[0]
    fw_bin   = args[1]
    out_ppm  = args[2]
    w        = int(args[3]) if len(args) > 3 else 128
    h        = int(args[4]) if len(args) > 4 else 128

    env = os.environ.copy()
    if capture_path:
        with open(capture_path, 'rb') as f:
            stream = f.read()
        # A real burst is tens of KB at ~2170 sim-cycles/byte for the wire
        # transfer alone, plus the enqueue_gap(8000000) boot delay and render
        # time — see run_and_dump's CTS_MAX_CYCLES comment in main.cpp.
        env.setdefault('CTS_MAX_CYCLES', '100000000')
    else:
        stream = build_0xad(build_mvp())

    with tempfile.NamedTemporaryFile(suffix='.bin', delete=False) as tmp:
        tmp.write(stream)
        uart_tmp = tmp.name

    try:
        result = subprocess.run(
            [sim_bin, '--cts-uart', uart_tmp, fw_bin, str(w), str(h)],
            capture_output=True, env=env
        )
        if result.returncode != 0:
            sys.stderr.buffer.write(result.stderr)
            sys.exit(result.returncode)

        rgb = result.stdout
        expected = w * h * 3
        if len(rgb) != expected:
            print(f'[cts_uart_render] expected {expected} bytes, got {len(rgb)}',
                  file=sys.stderr)
            if result.stderr:
                sys.stderr.buffer.write(result.stderr)
            sys.exit(1)

        ppm = rgb888_to_p3(rgb, w, h)
        with open(out_ppm, 'w') as f:
            f.write(ppm)

        print(f'[cts_uart_render] wrote {out_ppm} ({w}×{h})')
    finally:
        os.unlink(uart_tmp)


if __name__ == '__main__':
    main()
