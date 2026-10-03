#!/usr/bin/env python3
"""Markdown pass/fail table from the results.txt that scripts/cts_par.sh writes.

    scripts/cts_table.py RESULTS... [--depth N] [--total CASES]

Groups cases by the first N dotted components after "dEQP-VK." (default 1: api,
pipeline, ...). Several results files are merged. With --total, a "Not run" column
and total row are made against that many cases (e.g. the size of the mustpass list).
"""
import argparse, collections, re

COLS = ["Pass", "Fail", "NotSupported", "QualityWarning", "CompatibilityWarning",
        "InternalError", "ResourceError", "Crash", "Timeout"]

ap = argparse.ArgumentParser()
ap.add_argument("files", nargs="+")
ap.add_argument("--depth", type=int, default=1)
ap.add_argument("--total", type=int, default=0)
a = ap.parse_args()

rows = collections.defaultdict(collections.Counter)
seen = set()
for fn in a.files:
    for line in open(fn):
        parts = line.split(None, 2)
        if len(parts) < 2 or parts[0] not in COLS:
            continue
        res, case = parts[0], parts[1]
        if case in seen:
            continue
        seen.add(case)
        grp = ".".join(re.sub(r"^dEQP-VK\.", "", case).split(".")[:a.depth])
        rows[grp][res] += 1

used = [c for c in COLS if any(r[c] for r in rows.values())]
print("| Group | Cases run | " + " | ".join(used) + " |")
print("|---|---:|" + "---:|" * len(used))
tot = collections.Counter()
for g in sorted(rows):
    r = rows[g]
    n = sum(r.values())
    tot.update(r)
    print(f"| `{g}` | {n:,} | " + " | ".join(f"{r[c]:,}" for c in used) + " |")
n = sum(tot.values())
print(f"| **Total run** | **{n:,}** | " + " | ".join(f"**{tot[c]:,}**" for c in used) + " |")
if a.total:
    print(f"\n{a.total:,} cases in the list, {a.total - n:,} not run "
          f"({100.0 * n / a.total:.1f}% run); pass = {tot['Pass']:,} "
          f"({100.0 * tot['Pass'] / a.total:.2f}% of the list)")
