#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""Remove 0xB0 shader-upload packets from a captured borgvk UART stream.

Why: the golden render replays a capture that uploads borgc-compiled vert/frag
shaders, which OVERRIDE the firmware's baked ones within milliseconds of boot.
So the baked shaders -- now themselves borgc output -- are never executed by
any test, and the render passing says nothing about them.

Strip the uploads and the firmware keeps its baked pipeline, so the same
capture renders the same scene through shaders the build produced. If the two
images match, the offline compiler path and the driver path are equivalent for
this shader; if they differ, something real has diverged. It is also the only
thing that would catch the baked fragment shader overflowing instruction
memory, since an overridden shader is never loaded.

Packet framing (software/borg/borg_kernel.c): every marker has a FIXED length,
so the stream can be walked without interpreting payloads.
"""
import sys

RX_GEOM_MAX_VERTS = 16
RX_GEOM_MAX_TRIS = 12
RX_TEX_DIM = 64
RX_SHADER_MAX = 512
RX_PUSH_MAX_WORDS = 32

# marker -> total packet length in bytes, mirroring borg_kernel.c's `need`.
LEN = {
    0xAD: 66,
    0xAE: 1 + 2 + RX_GEOM_MAX_VERTS * 12 + RX_GEOM_MAX_TRIS * 3
          + RX_GEOM_MAX_TRIS * 24 + 1,
    0xAF: 1 + 1 + RX_TEX_DIM * 6 + 1,
    0xB0: 1 + 1 + 2 + RX_SHADER_MAX + 1,
    0xB1: 1,
    0xB2: 1 + 1 + 1 + RX_PUSH_MAX_WORDS * 4 + 1,
}


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: strip_shader_packets.py in.bin out.bin")
    data = open(sys.argv[1], 'rb').read()

    out = bytearray()
    kept, dropped, i = {}, 0, 0
    while i < len(data):
        marker = data[i]
        n = LEN.get(marker)
        if n is None:
            # Not a framing error we should paper over: a stream we cannot walk
            # exactly would be silently truncated, and the render would fail in
            # a way that looks like a hardware bug.
            sys.exit("strip_shader_packets: unknown marker 0x%02X at offset %d"
                     % (marker, i))
        if i + n > len(data):
            sys.exit("strip_shader_packets: truncated 0x%02X packet at offset %d "
                     "(need %d, have %d)" % (marker, i, n, len(data) - i))
        if marker == 0xB0:
            dropped += 1
        else:
            out += data[i:i + n]
            kept[marker] = kept.get(marker, 0) + 1
        i += n

    open(sys.argv[2], 'wb').write(out)
    desc = ", ".join("0x%02X x%d" % (m, c) for m, c in sorted(kept.items()))
    print("  kept %d bytes (%s); dropped %d shader upload(s)"
          % (len(out), desc, dropped))
    if dropped == 0:
        sys.exit("strip_shader_packets: no 0xB0 packets found -- the input "
                 "already runs the baked shaders, so this filter is a no-op "
                 "and the test it feeds would prove nothing")


if __name__ == '__main__':
    main()
