#!/usr/bin/env python3
# Decode a PPM printed as `od -An -v -tx1` between PPM-BEGIN and PPM-END in a UART capture.
# usage: uart_ppm_od.py capture.log out.ppm
import re, sys
text = open(sys.argv[1], errors="replace").read()
m = re.search(r"PPM-BEGIN\r?\n(.*?)PPM-END", text, re.S)
if not m:
    sys.exit("no complete PPM in capture")
hexes = re.findall(r"\b[0-9a-f]{2}\b", m.group(1))
open(sys.argv[2], "wb").write(bytes(int(h, 16) for h in hexes))
print(len(hexes), "bytes")
