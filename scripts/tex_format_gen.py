#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates the texel format tables from a tex_formats file.

    tex_format_gen.py TEX_FORMATS [--c F] [--scala F] [--rust F]

TEX_FORMATS (hardware/borg/isa/tex_formats.txt) has one format per line: code, name, texel bytes,
numeric type and, per channel R, G, B, A, its bit offset:width.
"""

import argparse
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent


def rows(path):
    out = []
    for line in open(path):
        f = line.split("#")[0].split()
        if f:
            ch = [tuple(int(x) for x in c.split(":")) for c in f[4:8]]
            out.append((int(f[0]), f[1], int(f[2]), f[3], ch))
    assert [r[0] for r in out] == list(range(1, len(out) + 1)), "codes run 1, 2, ..."
    return out


def header(path, license):
    try:
        src = path.resolve().relative_to(ROOT)
    except ValueError:
        src = path.name
    return ["// SPDX-FileCopyrightText: © 2026 Andreas Wendleder", f"// SPDX-License-Identifier: {license}",
            f"// GENERATED from {src} by scripts/tex_format_gen.py; do not edit.", ""]


def c(path, rs):
    o = header(path, "GPL-3.0-or-later") + ["#pragma once", "",
        "// Texel format codes for a texture descriptor, word 2 [19:14] (docs/B2_texture_unit.md)"]
    o += [f"#define BORG_TEX_FORMAT_{n:<28} {code}" for code, n, *_ in rs]
    return o


def scala(path, rs):
    o = header(path, "CERN-OHL-S-2.0") + ["package borg", "", "object TexFormatTable {",
        "  /** (code, name, texel bytes, numeric type, (offset, width) of R, G, B, A). */",
        "  val rows: Seq[(Int, String, Int, String, Seq[(Int, Int)])] = Seq("]
    o += [f'    ({code}, "{n}", {b}, "{k}", Seq({", ".join(f"({x}, {w})" for x, w in ch)})),' for code, n, b, k, ch in rs]
    return o + ["  )", "}"]


def rust(path, rs):
    o = header(path, "MIT") + ["// Name = code, texel bytes; included by src/borg/layout/tex_format.rs.", "formats! {"]
    o += [f"    {n} = {code}, {b};" for code, n, b, *_ in rs]
    return o + ["}"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tex_formats", type=pathlib.Path)
    for k in ("c", "scala", "rust"):
        ap.add_argument(f"--{k}", type=pathlib.Path)
    a = ap.parse_args()
    rs = rows(a.tex_formats)
    for k, gen in (("c", c), ("scala", scala), ("rust", rust)):
        out = getattr(a, k)
        if out:
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text("\n".join(gen(a.tex_formats, rs)) + "\n")


if __name__ == "__main__":
    main()
