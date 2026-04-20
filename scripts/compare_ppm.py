#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""
compare_ppm.py — Pixel-exact PPM comparison for Borg render regression tests.

Usage:
    python3 scripts/compare_ppm.py <candidate.ppm> <golden.ppm> [--max-diff N]

Exits 0 on pass, 1 on fail.
"""

import sys
import argparse


def parse_ppm(path: str):
    """Parse a P3 PPM file. Returns (width, height, pixels) where pixels is a
    flat list of (r, g, b) integer tuples."""
    with open(path, 'r', errors='replace') as f:
        lines = []
        for line in f:
            stripped = line.strip()
            if stripped and not stripped.startswith('#'):
                lines.append(stripped)

    tokens = ' '.join(lines).split()
    it = iter(tokens)
    magic = next(it)
    if magic != 'P3':
        raise ValueError(f"{path}: expected P3 PPM, got {magic!r}")
    w, h = int(next(it)), int(next(it))
    maxval = int(next(it))
    scale = 255 / maxval if maxval != 255 else 1
    pixels = []
    for _ in range(w * h):
        r = int(round(int(next(it)) * scale))
        g = int(round(int(next(it)) * scale))
        b = int(round(int(next(it)) * scale))
        pixels.append((r, g, b))
    return w, h, pixels


def compare(candidate: str, golden: str, max_diff: int = 0) -> bool:
    cw, ch, cpix = parse_ppm(candidate)
    gw, gh, gpix = parse_ppm(golden)

    if (cw, ch) != (gw, gh):
        print(f"  FAIL  size mismatch: candidate={cw}x{ch}, golden={gw}x{gh}")
        return False

    n_fail = 0
    worst = 0
    worst_pos = (-1, -1)
    for i, (cp, gp) in enumerate(zip(cpix, gpix)):
        diff = max(abs(cp[c] - gp[c]) for c in range(3))
        if diff > max_diff:
            n_fail += 1
            if diff > worst:
                worst = diff
                worst_pos = (i % cw, i // cw)

    if n_fail:
        print(f"  FAIL  {n_fail}/{cw*ch} pixels differ "
              f"(max_diff={max_diff}, worst={worst} at {worst_pos})")
        return False

    print(f"  PASS  {cw}x{ch} pixels match (max_diff={max_diff})")
    return True


def main():
    ap = argparse.ArgumentParser(description="Compare two P3 PPM files pixel-by-pixel")
    ap.add_argument("candidate", help="Rendered PPM to test")
    ap.add_argument("golden",    help="Known-good golden PPM")
    ap.add_argument("--max-diff", type=int, default=0,
                    help="Max allowed per-channel difference (default: 0 = exact)")
    args = ap.parse_args()

    try:
        ok = compare(args.candidate, args.golden, args.max_diff)
    except FileNotFoundError as e:
        print(f"  FAIL  File not found: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"  FAIL  Error: {e}")
        sys.exit(1)

    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
