#!/usr/bin/env python3
"""
Read exact per-frame cycle counts from the vkcube firmware.

Each frame the firmware prints (hex cycle counts, label-prefixed):

    M.. C.. D.. P..   t.. g.. h.. l.. a..

CPU-measured phases (rdcycle deltas):
    M = matrix   C = clear+texture+lighting   D = draw_cube   P = present(GPU)

HW perf counters (Borg, present-phase decomposition; frozen at last seq run):
    t = total (sequencer-busy)   g = frag (shader core running)
    h = flush (tile flusher)     l = stall (gpuMem.req && !ready)   a = dma

Cycle counts are exact and host/UART-timing independent.  ms = cyc / (MHz*1000).

Usage:  python3 scripts/measure_fps.py [/dev/ttyUSB0] [clock_mhz]
        (stop mouse_rotation.py first; run to a terminal, not a pipe)
"""

import sys
import re
import serial

PORT      = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
CLOCK_MHZ = float(sys.argv[2]) if len(sys.argv) > 2 else 25.0
BAUD      = 115200

TAGS = "MCDPtghla"
LINE_RE = re.compile(r"".join(rf"{t}([0-9a-f]+)\s+" for t in "MCDP")
                     + r"".join(rf"{t}([0-9a-f]+)\s*" for t in "tghla"))

PHASE = [("M", "matrix    "), ("C", "clr/tex/lt"), ("D", "draw_cube "), ("P", "present   ")]
PERF  = [("t", "  total   "), ("g", "  frag    "), ("h", "  flush   "),
         ("l", "  stall   "), ("a", "  dma     ")]


def main():
    ser = serial.Serial(PORT, BAUD, timeout=1)
    print(f"Reading cycle counts on {PORT} @ {BAUD}, clock {CLOCK_MHZ} MHz. Ctrl-C to stop.\n")
    acc = {t: 0 for t in TAGS}
    n = 0
    buf = b""
    while True:
        chunk = ser.read(128)
        if not chunk:
            continue
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            m = LINE_RE.search(line.decode("ascii", "ignore"))
            if not m:
                continue
            vals = [int(g, 16) for g in m.groups()]
            for t, v in zip(TAGS, vals):
                acc[t] += v
            n += 1
            if n >= 10:
                def ms(t): return acc[t] / n / (CLOCK_MHZ * 1000.0)
                frame = sum(ms(t) for t, _ in PHASE)
                fps = 1000.0 / frame if frame else 0
                print(f"── {fps:5.2f} fps   ({frame:6.1f} ms/frame, n={n})")
                print("  CPU phases (rdcycle):")
                for t, lbl in PHASE:
                    print(f"     {lbl} {ms(t):7.2f} ms  ({100*ms(t)/frame:4.1f}%)")
                print("  present decomposition (HW perf counters):")
                ptot = ms("t") or 1e-9
                for t, lbl in PERF:
                    print(f"     {lbl} {ms(t):7.2f} ms  ({100*ms(t)/ptot:4.1f}% of total)")
                print()
                acc = {t: 0 for t in TAGS}
                n = 0


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
