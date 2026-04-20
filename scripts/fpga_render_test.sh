#!/usr/bin/env bash
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# fpga_render_test.sh — Run triangle and vkcube on the FPGA and compare against
# golden images. Skips automatically if no pico-ice is attached (safe for CI).
#
# Exit codes:
#   0 — passed (or skipped because no hardware)
#   1 — failed (wrong output or unexpected error)

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOLDEN="$ROOT/simulation/golden"
FPGA="$ROOT/fpga"
COMPARE="python3 $ROOT/scripts/compare_ppm.py"

GREEN='\033[1;32m'; YELLOW='\033[1;33m'; RED='\033[1;31m'; RESET='\033[0m'
ok()   { echo -e "${GREEN}  PASS${RESET}  $*"; }
warn() { echo -e "${YELLOW}  SKIP${RESET}  $*"; }
fail() { echo -e "${RED}  FAIL${RESET}  $*"; }

# ---------------------------------------------------------------------------
# Hardware detection — skip if no pico-ice serial port present
# ---------------------------------------------------------------------------
if ! ls /dev/ttyACM* &>/dev/null; then
    warn "No /dev/ttyACM* device found — skipping FPGA render tests (no hardware)"
    exit 0
fi

# Also skip if mpremote isn't installed (e.g. minimal CI image)
if ! command -v mpremote &>/dev/null; then
    warn "mpremote not found — skipping FPGA render tests"
    exit 0
fi

echo "  pico-ice detected — running FPGA render tests"
FAIL=0

# ---------------------------------------------------------------------------
# Helper: run a make target, capture the PPM, compare to golden
# ---------------------------------------------------------------------------
run_test() {
    local target="$1"        # make target: triangle or vkcube
    local ppm="$2"           # expected PPM output: triangle_00.ppm or vkcube_00.ppm
    local golden="$GOLDEN/$ppm"

    echo ""
    echo "  --- FPGA $target ---"

    if [[ ! -f "$golden" ]]; then
        fail "$target: golden image not found at $golden"
        FAIL=1
        return
    fi

    # Run make, capture output to a temp log
    local log
    log=$(mktemp /tmp/fpga_${target}_XXXXXX.log)
    if ! make -C "$FPGA" "$target" >"$log" 2>&1; then
        fail "$target: make failed (see $log)"
        tail -20 "$log"
        FAIL=1
        return
    fi

    local candidate="$FPGA/$ppm"
    if [[ ! -f "$candidate" ]]; then
        fail "$target: PPM not produced at $candidate"
        FAIL=1
        return
    fi

    if $COMPARE "$candidate" "$golden" --max-diff 1; then
        ok "$target render matches golden"
    else
        fail "$target render differs from golden"
        FAIL=1
    fi

    rm -f "$log"
}

run_test triangle  triangle_00.ppm
run_test vkcube    vkcube_00.ppm

echo ""
if [[ $FAIL -eq 0 ]]; then
    echo -e "${GREEN}  All FPGA render tests passed.${RESET}"
    exit 0
else
    echo -e "${RED}  One or more FPGA render tests FAILED.${RESET}"
    exit 1
fi
