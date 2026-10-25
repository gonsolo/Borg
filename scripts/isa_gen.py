#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates instruction encodings from opcode files in riscv-opcodes syntax.

    isa_gen.py ISA_DIR [--c F] [--scala F --package P --object O] [--rust F [--ir NAMES]]

ISA_DIR holds fields.csv, the opcode files and optionally csrs.csv (riscv-opcodes' format:
number, "name" per line). --ir names a file of compiler-only operations, one name per line:
they join the Rust Op enum with no encoding (0). hardware/borg/isa for the Borg shader ISA,
hardware/hutt/isa for Hutt's RISC-V subset (riscv-opcodes' own extension files). Python reads the
source directly: encode(ISA_DIR, "store", rs1=0, rs2=1).

fields.csv: name, bits, role. bits are word ranges, MSB part first, each optionally naming the
operand bits it holds (31=12 30..25=10..5); without them the operand's bits run MSB first. role
is empty for an operand, "const" for a fixed range named per instruction (FUNCT3_ADD), or the
riscv-opcodes fields an operand stands for (boff for bimm12hi bimm12lo).
"""

import argparse
import csv
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def rng(spec):
    hi, _, lo = spec.partition("..")
    return int(hi), int(lo or hi)


def load_fields(d):
    fields, consts, replaces = {}, {}, {}
    with open(d / "fields.csv") as f:
        for row in csv.DictReader(f):
            name, role = row["name"], (row["role"] or "").strip()
            if role == "const":
                consts[name] = rng(row["bits"])
                continue
            specs = [p.partition("=") for p in row["bits"].split()]
            width = sum(rng(w)[0] - rng(w)[1] + 1 for w, _, _ in specs)
            parts, off = [], width
            for w, _, o in specs:
                hi, lo = rng(w)
                off -= hi - lo + 1
                parts.append((hi, lo, rng(o)[1] if o else off))
            fields[name] = parts
            if role:
                replaces[name] = role.split()
    return fields, consts, replaces


def width(fields, name):
    return max(olo + hi - lo + 1 for hi, lo, olo in fields[name])


def load_opcodes(d, fields, replaces):
    ops, pseudo = [], []
    files = sorted(p for p in d.iterdir() if p.is_file() and "." not in p.name and p.name != "LICENSE")
    for path in files:
        for n, line in enumerate(open(path), 1):
            toks = line.split("#", 1)[0].split()
            if not toks or toks[0] == "$import":
                continue
            where = f"{path.name}:{n}"
            base = None
            if toks[0] == "$pseudo_op":
                base, toks = toks[1], toks[2:]
            name, args, fixed = toks[0], [], []
            for t in toks[1:]:
                if "=" in t:
                    r, v = t.split("=")
                    fixed.append((*rng(r), int(v, 0)))
                else:
                    args.append(t)
            for new, old in replaces.items():
                if all(o in args for o in old):
                    args[args.index(old[0])] = new
                    args = [a for a in args if a not in old]
            for a in args:
                if a not in fields:
                    sys.exit(f"{where}: unknown field {a}")
            match = mask = used = 0
            for hi, lo, v in fixed:
                w = (1 << (hi - lo + 1)) - 1
                if v > w:
                    sys.exit(f"{where}: {v:#x} does not fit {hi}..{lo}")
                match |= v << lo
                mask |= w << lo
            for hi, lo in [(p[0], p[1]) for a in args for p in fields[a]] + [(hi, lo) for hi, lo, _ in fixed]:
                w = ((1 << (hi - lo + 1)) - 1) << lo
                if used & w:
                    sys.exit(f"{where}: {name} covers bits {hi}..{lo} twice")
                used |= w
            if used != 0xFFFFFFFF:
                sys.exit(f"{where}: {name} leaves bits {~used & 0xFFFFFFFF:#010x} undefined")
            op = dict(name=name, ident=name.upper().replace(".", "_"), args=args, fixed=fixed,
                      match=match, mask=mask)
            (pseudo if base else ops).append(op)
    for i, a in enumerate(ops):
        for b in ops[i + 1:]:
            if not (a["match"] ^ b["match"]) & a["mask"] & b["mask"]:
                sys.exit(f"{a['name']} and {b['name']} overlap")
    return ops, pseudo


def load(d):
    d = pathlib.Path(d)
    fields, consts, replaces = load_fields(d)
    ops, pseudo = load_opcodes(d, fields, replaces)
    return fields, consts, ops, pseudo


def load_ir(path):
    names = [line.split("#", 1)[0].strip() for line in open(path)] if path else []
    return [n for n in names if n]


def load_csrs(d):
    path, csrs = pathlib.Path(d) / "csrs.csv", []
    if not path.exists():
        return csrs
    for n, line in enumerate(open(path), 1):
        line = line.split("#", 1)[0].strip()
        if line:
            num, name = (t.strip() for t in line.split(","))
            csrs.append((int(num, 0), name.strip('"')))
    for k in (0, 1):
        seen = [c[k] for c in csrs]
        if len(set(seen)) != len(seen):
            sys.exit(f"csrs.csv: {sorted(x for x in seen if seen.count(x) > 1)[0]} twice")
    return csrs


def const_value(op, hi, lo):
    """The value of word bits hi..lo when fixed bits wholly inside them cover them."""
    inner = [(h, l) for h, l, _ in op["fixed"] if h <= hi and l >= lo]
    if sum(h - l + 1 for h, l in inner) != hi - lo + 1:
        return None
    return op["match"] >> lo & (1 << (hi - lo + 1)) - 1


def place(fields, name, var, u32):
    terms = []
    for hi, lo, olo in fields[name]:
        m = (1 << (hi - lo + 1)) - 1
        v = f"({var} >> {olo})" if olo else var
        terms.append(f"(({v} & {m:#x}{u32}) << {lo})")
    return " | ".join(terms)


def header(d, comment):
    return [f"{comment} GENERATED from {d.relative_to(ROOT)} by scripts/isa_gen.py; do not edit."]


def gen_c(d, fields, consts, ops, pseudo, csrs):
    o = ["// SPDX-FileCopyrightText: © 2026 Andreas Wendleder",
         "// SPDX-License-Identifier: GPL-3.0-or-later"] + header(d, "//") + ["",
         "#pragma once", "#include <stdint.h>", ""]
    for op in ops:
        n = op["ident"]
        o.append(f"#define BORG_MATCH_{n} {op['match']:#010x}U")
        o.append(f"#define BORG_MASK_{n} {op['mask']:#010x}U")
        body = " | ".join([f"BORG_MATCH_{n}"] + [place(fields, a, f"(uint32_t)({a})", "U") for a in op["args"]])
        o.append(f"#define BORG_INSTR_{n}({', '.join(op['args'])}) ({body})")
    o.append("")
    for p in pseudo:
        o.append(f"#define BORG_INSTR_{p['ident']} {p['match']:#010x}U")
    o.append("")
    o.append("/* X(NAME) for every instruction. */")
    o.append("#define BORG_OPCODES(X) " + " ".join(f"X({op['ident']})" for op in ops))
    if csrs:
        o.append("")
    o += [f"#define BORG_CSR_{name.upper()} {num:#05x}U" for num, name in csrs]
    return "\n".join(o) + "\n"


SCALA_KEYWORDS = {"private", "type", "val", "var", "def", "class", "object"}


def scala_arg(a):
    return f"`{a}`" if a in SCALA_KEYWORDS else a


def gen_scala(d, fields, consts, ops, pseudo, csrs, package, obj):
    o = ["// SPDX-FileCopyrightText: © 2026 Andreas Wendleder",
         "// SPDX-License-Identifier: CERN-OHL-S-2.0"] + header(d, "//") + ["",
         f"package {package}", "",
         f"object {obj} {{"]
    for name, parts in fields.items():
        if len(parts) == 1:
            o.append(f"  val FIELD_{name.upper()} = ({parts[0][0]}, {parts[0][1]})")
    for op in ops:
        n = op["ident"]
        o.append(f"  val MATCH_{n} = {op['match']:#010x}L")
        o.append(f"  val MASK_{n} = {op['mask']:#010x}L")
        for c, (hi, lo) in consts.items():
            v = const_value(op, hi, lo)
            if v is not None:
                o.append(f"  val {c.upper()}_{n} = {v:#04x}")
    for p in pseudo:
        if not p["args"]:
            o.append(f"  val {p['ident']} = {p['match']:#010x}L")
    o.append("")
    for op in ops:
        params = ", ".join(f"{scala_arg(a)}: Int = 0" for a in op["args"])
        body = " | ".join([f"MATCH_{op['ident']}"] +
                          [place(fields, a, f"{scala_arg(a)}.toLong", "L") for a in op["args"]])
        o.append(f"  def {op['ident']}({params}): BigInt = BigInt({body})")
    o.append("}")
    if csrs:
        o += ["", "object Csr {"] + [f"  val {snake_camel(name)} = {num:#05x}" for num, name in csrs] + ["}"]
    return "\n".join(o) + "\n"


def snake_camel(name):
    return "".join(p[:1].upper() + p[1:] for p in name.split("_"))


def camel(name):
    return "".join(p[:1].upper() + p[1:] for p in name.split("."))


def gen_rust(d, fields, consts, ops, pseudo, csrs, ir=()):
    ops = ops + [dict(name=n, args=[], match=0, mask=0) for n in ir]
    o = ["// Copyright © 2026 Andreas Wendleder",
         "// SPDX-License-Identifier: MIT"] + header(d, "//") + ["",
         "#![allow(dead_code)]", "",
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
        if " | " not in body and body.startswith("(("):
            body = body[1:-1]
        o.append(f"            Op::{camel(op['name'])} => {body},")
    o += ["        }", "    }", "}", ""]
    for p in pseudo:
        o.append(f"pub(crate) const {p['ident']}: u32 = {p['match']:#010x};")
    o += [f"pub(crate) const CSR_{name.upper()}: u32 = {num:#05x};" for num, name in csrs]
    return "\n".join(o) + "\n"


_cache = {}


def encode(isa, name, **operands):
    """The word of instruction `name` of the ISA in directory `isa`; operands not given are 0.
    An operand may be negative down to minus half its range (a signed immediate)."""
    d = pathlib.Path(isa).resolve()
    if d not in _cache:
        fields, _, ops, _ = load(d)
        _cache[d] = fields, {op["name"]: op for op in ops}
    fields, ops = _cache[d]
    op = ops[name]
    word = op["match"]
    for a, v in operands.items():
        if a not in op["args"]:
            raise ValueError(f"{name} has no operand {a}")
        w = width(fields, a)
        if not -(1 << (w - 1)) <= v < 1 << w:
            raise ValueError(f"{name}: {a} = {v} does not fit")
        for hi, lo, olo in fields[a]:
            word |= (v >> olo & (1 << (hi - lo + 1)) - 1) << lo
    return word


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("isa")
    ap.add_argument("--c")
    ap.add_argument("--scala")
    ap.add_argument("--package")
    ap.add_argument("--object")
    ap.add_argument("--rust")
    ap.add_argument("--ir")
    a = ap.parse_args()
    d = pathlib.Path(a.isa).resolve()
    fields, consts, ops, pseudo = load(d)
    csrs = load_csrs(d)
    outs = ((a.c, lambda: gen_c(d, fields, consts, ops, pseudo, csrs)),
            (a.scala, lambda: gen_scala(d, fields, consts, ops, pseudo, csrs, a.package, a.object)),
            (a.rust, lambda: gen_rust(d, fields, consts, ops, pseudo, csrs, load_ir(a.ir))))
    for path, gen in outs:
        if path:
            pathlib.Path(path).write_text(gen())

if __name__ == "__main__":
    main()
