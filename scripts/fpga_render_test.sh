#!/usr/bin/env bash
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# fpga_render_test.sh — Run triangle and vkcube on the FPGA and compare against
# FPGA-specific golden images. Skips automatically if no pico-ice is attached.
#
# Golden images live in simulation/golden/fpga/ (separate from sim goldens because
# real FPGA hardware FP16 rounding differs slightly from software simulation).
# On first run (no golden exists) the FPGA output is SAVED as the new golden.
#
# Exit codes:
#   0 — passed (or skipped because no hardware)
#   1 — failed (wrong output or unexpected error)

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOLDEN="$ROOT/simulation/golden/fpga"
FPGA="$ROOT/fpga"
COMPARE="python3 $ROOT/scripts/compare_ppm.py"

GREEN='\033[1;32m'; YELLOW='\033[1;33m'; RED='\033[1;31m'; RESET='\033[0m'
ok()   { echo -e "${GREEN}  PASS${RESET}  $*"; }
warn() { echo -e "${YELLOW}  SKIP${RESET}  $*"; }
info() { echo -e "${YELLOW}  INFO${RESET}  $*"; }
fail() { echo -e "${RED}  FAIL${RESET}  $*"; }

# ---------------------------------------------------------------------------
# Hardware detection — skip if no pico-ice serial port present
# ---------------------------------------------------------------------------
if ! ls /dev/ttyACM* &>/dev/null; then
    warn "No /dev/ttyACM* device found — skipping FPGA render tests (no hardware)"
    exit 0
fi

if ! command -v mpremote &>/dev/null; then
    warn "mpremote not found — skipping FPGA render tests"
    exit 0
fi

mkdir -p "$GOLDEN"
echo "  pico-ice detected — running FPGA render tests"
FAIL=0

# ---------------------------------------------------------------------------
# Helper: run a make target, compare to FPGA golden (or save if first run)
# ---------------------------------------------------------------------------
run_test() {
    local target="$1"
    local ppm="$2"
    local golden_img="$GOLDEN/$ppm"

    echo ""
    echo "  --- FPGA $target ---"

    local log
    log=$(mktemp /tmp/fpga_${target}_XXXXXX.log)
    if ! make -C "$FPGA" "$target" >"$log" 2>&1; then
        fail "$target: make failed"
        tail -20 "$log"
        rm -f "$log"
        FAIL=1
        return
    fi

    local candidate="$FPGA/$ppm"
    if [[ ! -f "$candidate" ]]; then
        fail "$target: PPM not produced at $candidate"
        rm -f "$log"
        FAIL=1
        return
    fi

    if [[ ! -f "$golden_img" ]]; then
        # First run — save as FPGA golden
        cp "$candidate" "$golden_img"
        info "$target: no FPGA golden existed — saved $golden_img as new golden"
        info "  Commit simulation/golden/fpga/ to lock it in."
    else
        # max-diff=8: tolerates ~1 ULP FP16 rounding variation on real hardware
        # max-fail-pixels=2: tolerates minor edge discrepancies (aliasing/rounding)
        if $COMPARE "$candidate" "$golden_img" --max-diff 8 --max-fail-pixels 2; then
            ok "$target render matches FPGA golden"
        else
            fail "$target render differs from FPGA golden"
            FAIL=1
        fi
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
