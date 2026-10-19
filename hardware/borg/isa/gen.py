#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates the Borg ISA's encodings for every consumer from `opcodes` and `fields.csv`.

    gen.py [--check] --c borg_isa.h --scala Opcodes.scala --rust opcodes.rs

--check compares with the files instead of writing them and fails when one differs.
"""

import argparse
import csv
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
SOURCE = "hardware/borg/isa/opcodes"


def bits(spec):
    hi, _, lo = spec.partition("..")
    return int(hi), int(lo or hi)


def load_fields():
    fields = {}
    with open(HERE / "fields.csv") as f:
        for row in csv.DictReader(f):
            fields[row["name"]] = [bits(p) for p in row["bits"].split()]
    return fields


def load_opcodes(fields):
    ops, pseudo = [], []
    for n, line in enumerate(open(HERE / "opcodes"), 1):
        toks = line.split("#", 1)[0].split()
        if not toks:
            continue
        base = None
        if toks[0] == "$pseudo_op":
            base, toks = toks[1], toks[2:]
        name, args, fixed = toks[0], [], []
        for t in toks[1:]:
            if "=" in t:
                r, v = t.split("=")
                fixed.append((*bits(r), int(v, 0)))
            elif t in fields:
                args.append(t)
            else:
                sys.exit(f"opcodes:{n}: unknown field {t}")
        match = mask = 0
        used = 0
        for hi, lo, v in fixed:
            w = (1 << (hi - lo + 1)) - 1
            if v > w:
                sys.exit(f"opcodes:{n}: {v:#x} does not fit {hi}..{lo}")
            match |= v << lo
            mask |= w << lo
        for r in [p for a in args for p in fields[a]] + [(hi, lo) for hi, lo, _ in fixed]:
            w = ((1 << (r[0] - r[1] + 1)) - 1) << r[1]
            if used & w:
                sys.exit(f"opcodes:{n}: {name} covers bits {r[0]}..{r[1]} twice")
            used |= w
        if used != 0xFFFFFFFF:
            sys.exit(f"opcodes:{n}: {name} leaves bits {~used & 0xFFFFFFFF:#010x} undefined")
        op = dict(name=name, args=args, fixed=fixed, match=match, mask=mask)
        (pseudo if base else ops).append(op)
    for i, a in enumerate(ops):
        for b in ops[i + 1:]:
            if not (a["match"] ^ b["match"]) & a["mask"] & b["mask"]:
                sys.exit(f"opcodes: {a['name']} and {b['name']} overlap")
    return ops, pseudo


def parts(fields, name):
    """(word hi, word lo, operand lo) of each part of a field, most significant first."""
    out, off = [], sum(hi - lo + 1 for hi, lo in fields[name])
    for hi, lo in fields[name]:
        off -= hi - lo + 1
        out.append((hi, lo, off))
    return out


def place(fields, name, var, u32):
    terms = []
    for hi, lo, off in parts(fields, name):
        m = (1 << (hi - lo + 1)) - 1
        v = f"({var} >> {off})" if off else var
        terms.append(f"(({v} & {m:#x}{u32}) << {lo})")
    return " | ".join(terms)


def fixed_of(op, hi, lo):
    return next((v for h, l, v in op["fixed"] if (h, l) == (hi, lo)), None)


HEADER = "GENERATED from " + SOURCE + " by hardware/borg/isa/gen.py; do not edit."


def gen_c(fields, ops, pseudo):
    o = ["// SPDX-FileCopyrightText: © 2026 Andreas Wendleder",
         "// SPDX-License-Identifier: GPL-3.0-or-later",
         "// " + HEADER, "",
         "#pragma once", "#include <stdint.h>", ""]
    for op in ops:
        n = op["name"].upper()
        o.append(f"#define BORG_MATCH_{n} {op['match']:#010x}U")
        o.append(f"#define BORG_MASK_{n} {op['mask']:#010x}U")
        body = " | ".join([f"BORG_MATCH_{n}"] + [place(fields, a, f"(uint32_t)({a})", "U") for a in op["args"]])
        o.append(f"#define BORG_INSTR_{n}({', '.join(op['args'])}) ({body})")
    o.append("")
    for p in pseudo:
        o.append(f"#define BORG_INSTR_{p['name'].upper()} {p['match']:#010x}U")
    o.append("")
    o.append("/* X(NAME) for every instruction. */")
    o.append("#define BORG_OPCODES(X) " + " ".join(f"X({op['name'].upper()})" for op in ops))
    return "\n".join(o) + "\n"


def gen_scala(fields, ops, pseudo):
    o = ["// SPDX-FileCopyrightText: © 2026 Andreas Wendleder",
         "// SPDX-License-Identifier: CERN-OHL-S-2.0",
         "// " + HEADER, "",
         "package borg", "",
         "object Opcodes {"]
    for name, rs in fields.items():
        if len(rs) == 1:
            o.append(f"  val FIELD_{name.upper()} = ({rs[0][0]}, {rs[0][1]})")
    for op in ops:
        n = op["name"].upper()
        o.append(f"  val MATCH_{n} = {op['match']:#010x}L")
        o.append(f"  val MASK_{n} = {op['mask']:#010x}L")
        for (hi, lo), what in (((6, 0), "OPCODE"), ((31, 25), "FUNCT7"), ((26, 25), "FUNCT2")):
            v = fixed_of(op, hi, lo)
            if v is not None:
                o.append(f"  val {what}_{n} = {v:#04x}")
    for p in pseudo:
        o.append(f"  val {p['name'].upper()} = {p['match']:#010x}L")
    o.append("}")
    return "\n".join(o) + "\n"


def camel(name):
    return name[0].upper() + name[1:]


def gen_rust(fields, ops, pseudo):
    o = ["// Copyright © 2026 Andreas Wendleder",
         "// SPDX-License-Identifier: MIT",
         "// " + HEADER, "",
         "#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]",
         "pub(crate) enum Op {"]
    o += [f"    {camel(op['name'])}," for op in ops]
    o += ["}", "",
          "/// The operand fields of a word; an instruction encodes those it has.",
          "#[derive(Clone, Copy, Default)]",
          "pub(crate) struct Fields {"]
    o += [f"    pub {f}: u32," for f in fields]
    o += ["}", "", "impl Op {",
          f"    pub(crate) const ALL: [Op; {len(ops)}] = ["]
    o += [f"        Op::{camel(op['name'])}," for op in ops]
    o += ["    ];", "",
          "    pub(crate) fn name(self) -> &'static str {", "        match self {"]
    o += [f"            Op::{camel(op['name'])} => \"{op['name'].upper()}\"," for op in ops]
    o += ["        }", "    }", "",
          "    pub(crate) fn match_bits(self) -> u32 {", "        match self {"]
    o += [f"            Op::{camel(op['name'])} => {op['match']:#010x}," for op in ops]
    o += ["        }", "    }", "",
          "    pub(crate) fn mask(self) -> u32 {", "        match self {"]
    o += [f"            Op::{camel(op['name'])} => {op['mask']:#010x}," for op in ops]
    o += ["        }", "    }", "",
          "    pub(crate) fn encode(self, f: &Fields) -> u32 {",
          "        self.match_bits() | match self {"]
    for op in ops:
        body = " | ".join(place(fields, a, f"f.{a}", "") for a in op["args"]) or "0"
        o.append(f"            Op::{camel(op['name'])} => {body},")
    o += ["        }", "    }", "}", ""]
    for p in pseudo:
        o.append(f"pub(crate) const {p['name'].upper()}: u32 = {p['match']:#010x};")
    return "\n".join(o) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--c")
    ap.add_argument("--scala")
    ap.add_argument("--rust")
    a = ap.parse_args()
    fields = load_fields()
    ops, pseudo = load_opcodes(fields)
    stale = []
    for path, gen in ((a.c, gen_c), (a.scala, gen_scala), (a.rust, gen_rust)):
        if not path:
            continue
        text = gen(fields, ops, pseudo)
        p = pathlib.Path(path)
        if a.check:
            if not p.exists() or p.read_text() != text:
                stale.append(path)
        else:
            p.write_text(text)
    if stale:
        sys.exit("out of date, run make isa: " + " ".join(stale))


if __name__ == "__main__":
    main()
