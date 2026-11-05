#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""borg_layout.h from the memory layout table: layout_gen.py hardware/rdl/layout.txt --c OUT."""
import sys


def generate(src):
    vals, out = {}, ["// Generated from hardware/rdl/layout.txt by scripts/layout_gen.py; do not edit.", "#pragma once"]
    for raw in open(src):
        line = raw.rstrip()
        if not line or line.startswith("#"):
            out.append("//" + line[1:] if line else "")
            continue
        body, _, note = line.partition("#")
        name, expr = body.split(None, 1)
        v = eval(expr, {"__builtins__": {}}, vals)
        if name in vals or not isinstance(v, int) or v < 0:
            sys.exit(f"{src}: bad entry {name}")
        vals[name] = v
        text = f"0x{v:X}u" if v >= 4096 else str(v)
        out.append(f"#define {name:<27} {text}" + (f"  //{note.rstrip()}" if note else ""))
    return "\n".join(out) + "\n", vals


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[2] != "--c":
        sys.exit("usage: layout_gen.py LAYOUT --c OUT")
    text, _ = generate(sys.argv[1])
    with open(sys.argv[3], "w") as f:
        f.write(text)
