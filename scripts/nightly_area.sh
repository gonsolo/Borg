#!/usr/bin/env bash
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0
#
# Nightly wafer.space area/timing measurement.
#
# Runs the LibreLane flow on whatever is currently checked out and appends one
# line per night to a CSV, so the cost of a day's hardware work is a trend
# rather than a number discovered once at the end. That ordering is the whole
# point: the slot is a hard boundary, and finding out at day 90 that the design
# no longer fits means having already spent the budget building what must then
# be cut.
#
# DRC/antenna are skipped (`librelane-nodrc`). This is a fit-and-timing probe,
# not signoff -- the question it answers nightly is "does it still fit and does
# it still close", and the DRC steps are the expensive ones that do not bear on
# either. Run the full flow before the freeze, not every night.
#
# Usage:  scripts/nightly_area.sh [worktree]
# Cron:   see `crontab -l` -- installed to run overnight.

set -uo pipefail

WORKTREE="${1:-/home/gonsolo/work/Borg-fp32}"
LOGDIR="$HOME/borg-area-history"
CSV="$LOGDIR/area_history.csv"
mkdir -p "$LOGDIR"

STAMP="$(date -Iseconds)"
cd "$WORKTREE" || { echo "no worktree at $WORKTREE" >&2; exit 1; }

BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
SHA="$(git rev-parse --short HEAD 2>/dev/null || echo '?')"
RUNLOG="$LOGDIR/run_$(date +%Y%m%d_%H%M%S)_${SHA}.log"

echo "[nightly-area] $STAMP  $BRANCH@$SHA -> $RUNLOG"

# direnv exec picks up the nix devshell; a bare command does not get its PATH.
direnv exec "$WORKTREE" make -C "$WORKTREE/asic/wafer.space" librelane-nodrc \
  > "$RUNLOG" 2>&1
RC=$?

# LibreLane writes per-run metrics; take the newest regardless of run-tag
# naming, since that has changed between versions.
METRICS="$(find "$WORKTREE/asic/wafer.space" -name metrics.json -newermt '-1 day' \
             -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)"

# Pull the handful of numbers that actually decide "does this tape out".
# Missing keys report as NA rather than failing: a metric being renamed
# upstream must not silently turn into a zero that looks like good news.
read -r UTIL AREA INSTS SLACK <<<"$(
  python3 - "$METRICS" <<'PY'
import json, sys
keys = [
    ("design__instance__utilization", "design__instance__utilization__stdcell"),
    ("design__die__area", "design__core__area"),
    ("design__instance__count", "design__instance__count__stdcell"),
    ("timing__setup__ws", "timing__setup__ws__corner:nom_tt_025C_5v00"),
]
try:
    m = json.load(open(sys.argv[1]))
except Exception:
    print("NA NA NA NA"); raise SystemExit
out = []
for names in keys:
    v = next((m[n] for n in names if n in m), None)
    if v is None:  # fall back to any key with the same prefix
        pref = names[0]
        v = next((m[k] for k in m if k.startswith(pref)), None)
    out.append("NA" if v is None else (f"{v:.4f}" if isinstance(v, float) else str(v)))
print(" ".join(out))
PY
)"

if [ ! -f "$CSV" ]; then
  echo "timestamp,branch,sha,exit,utilization,die_area,instances,setup_ws,log" > "$CSV"
fi
echo "$STAMP,$BRANCH,$SHA,$RC,$UTIL,$AREA,$INSTS,$SLACK,$RUNLOG" >> "$CSV"

echo "[nightly-area] rc=$RC util=$UTIL area=$AREA insts=$INSTS ws=$SLACK"
[ "$RC" -eq 0 ] || echo "[nightly-area] FLOW FAILED -- see $RUNLOG" >&2
exit "$RC"
