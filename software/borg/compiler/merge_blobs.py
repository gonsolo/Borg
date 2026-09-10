#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""Merge borgc-cli output into shader_blobs.h, preserving rasterize_borg.

rasterize_borg is hand-written Borg ISA with no assembler in-tree, so it is
carried across verbatim from the existing header rather than regenerated. That
makes this a merge rather than a write, and means losing the existing file
loses the rasterizer -- hence the hard failure if it cannot be found.
"""
import re
import sys

HEADER = """// Embedded SPIR-B shader blobs for the firmware's standalone pipeline.
//
// vert_borg / frag_borg are GENERATED -- do not edit them by hand. They are
// borgc output for Vulkan-Tools' cube.vert / cube.frag, the same shaders
// borgvk compiles at submit time, produced by the same compiler through the
// same NIR pass list (borgc-cli, sharing borg_nir_passes.c with the driver).
//
//   regenerate:  make -C software/borg/compiler regen BORGC_CLI=<path>
//
// The previous generation of these blobs came from a glslangValidator ->
// spirv-dis -> python pipeline that was deleted along with its GLSL sources,
// leaving an artifact nobody could rebuild. It had already silently gone stale
// against a wire-format change by the time that was noticed. Hence generated,
// with the command recorded here.
//
// rasterize_borg is NOT generated: it is hand-written Borg ISA (rasterize.s)
// and there is no assembler for it in-tree, so it stays checked in verbatim.
#pragma once
#include <stdint.h>

"""


def block(text, name, where):
    m = re.search(r'unsigned char %s\[\] = \{.*?\};\nunsigned int %s_len = \d+;'
                  % (name, name), text, re.S)
    if not m:
        sys.exit("merge_blobs: no %s block in %s" % (name, where))
    return m.group(0)


def main():
    if len(sys.argv) != 4:
        sys.exit("usage: merge_blobs.py gen_vert.h gen_frag.h shader_blobs.h")
    vert_h, frag_h, out = sys.argv[1:]

    try:
        existing = open(out).read()
    except OSError:
        sys.exit("merge_blobs: cannot read %s -- it carries the hand-written "
                 "rasterize_borg, which nothing can regenerate" % out)

    parts = [block(open(vert_h).read(), 'vert_borg', vert_h),
             block(existing, 'rasterize_borg', out),
             block(open(frag_h).read(), 'frag_borg', frag_h)]
    open(out, 'w').write(HEADER + '\n'.join(parts) + '\n')
    for p in parts:
        n, ln = re.search(r'unsigned char (\w+)\[.*?_len = (\d+);', p, re.S).groups()
        print("  %-16s %s bytes" % (n, ln))


if __name__ == '__main__':
    main()
