#!/bin/sh
# Runs bench.bin on the board (NexysLinuxTop) and prints the timings.
cd "$(dirname "$0")/.." && python3 -I hutt_run.py bench/bench.bin --peek 0x100100 44 --wait 20 | tail -1 | python3 -c "
import sys, ast, struct
c, r, h, n, s, m = struct.unpack('<QQQQQI', ast.literal_eval(sys.stdin.read().strip()))
assert m == 0x600dc0de, hex(m)
print(f'copy 64 KB      {c:8} cycles  {c/8192:6.1f} per ld+sd')
print(f'read 16 KB x16  {r:8} cycles  {r/32768:6.1f} per ld')
print(f'same ld x4096   {h:8} cycles  {h/4096:6.1f} per ld')
print(f'no memory x4096 {n:8} cycles  {n/4096:6.1f} per iteration (3 instructions)')"
