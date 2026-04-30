#!/usr/bin/env bash
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# mpremote.sh — Rock-solid wrapper around mpremote for pico-ice FPGA work.
#
# Features:
#   - Per-user lockfile: prevents two make targets fighting over /dev/ttyACM0
#   - Pre-flight USB check: fails fast if device is already dead
#   - SIGTERM → wait → SIGKILL: gives mpremote time to clean up USB state
#   - Automatic USB recovery on timeout or failure (up to 3 attempts)
#   - Centralized /dev/sda unmount so USB storage never blocks operation
#   - setsid isolation: USB events cannot SIGHUP this script or the Makefile
#
# Usage:
#   ./mpremote.sh [timeout_secs] <mpremote_args...>
#
# Examples:
#   ./mpremote.sh 120 mount . + run host/program_bitstream.py
#   ./mpremote.sh 60  cp borg_mmio.py :borg_mmio.py

# ---------------------------------------------------------------------------
# Detach from the calling terminal's process group so that a SIGHUP caused
# by a USB-triggered terminal disconnect cannot kill the make session.
# ---------------------------------------------------------------------------
if [[ -z "${BORG_MPREMOTE_DETACHED:-}" ]]; then
    export BORG_MPREMOTE_DETACHED=1
    exec setsid bash "$0" "$@"
fi

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECOVER="$SCRIPT_DIR/usb_recover.sh"

BOLD='\033[1m'
RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RESET='\033[0m'

log()  { echo -e "${BOLD}[MPREMOTE]${RESET} $*"; }
ok()   { echo -e "${GREEN}[MPREMOTE] ✓${RESET} $*"; }
warn() { echo -e "${YELLOW}[MPREMOTE] ⚠${RESET} $*"; }
err()  { echo -e "${RED}[MPREMOTE] ✗${RESET} $*"; }

# ---------------------------------------------------------------------------
# Parse optional timeout (first arg if purely numeric)
# ---------------------------------------------------------------------------
TIMEOUT=120
if [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; then
    TIMEOUT="$1"
    shift
fi

if [[ $# -eq 0 ]]; then
    echo "Usage: $0 [timeout_secs] <mpremote args...>"
    exit 1
fi

# ---------------------------------------------------------------------------
# Per-user lockfile: only one mpremote session at a time on this device.
# flock -n fails immediately if already locked — no silent queue buildup.
# ---------------------------------------------------------------------------
LOCKFILE="/tmp/borg-pico-ice-${USER}.lock"
exec 9>"$LOCKFILE"
if ! flock -n 9; then
    err "Another mpremote/borg session is already running for user $USER."
    err "Lock held by: $(cat "${LOCKFILE}.pid" 2>/dev/null || echo 'unknown')"
    err "Wait for it to finish, or remove $LOCKFILE to force-release."
    exit 1
fi
echo $$ > "${LOCKFILE}.pid"
trap 'rm -f "${LOCKFILE}.pid"' EXIT

# ---------------------------------------------------------------------------
# Unmount /dev/sda if present — prevents USB storage from blocking the
# CDC-ACM serial path during heavy SPI flash operations.
# ---------------------------------------------------------------------------
unmount_sda() {
    if [[ -b /dev/sda ]]; then
        warn "/dev/sda detected — unmounting to avoid USB storage conflicts ..."
        umount /dev/sda 2>/dev/null || true
    fi
}

unmount_sda

# ---------------------------------------------------------------------------
# Pre-flight: check if the pico-ice serial port exists at all before spending
# time connecting. Fail fast rather than sitting through a full timeout.
# ---------------------------------------------------------------------------
preflight_check() {
    local tty
    tty=$(ls /dev/serial/by-id/usb-*pico-ice*-if00* 2>/dev/null | head -n1 || true)
    if [[ -z "$tty" && ! -e /dev/ttyACM0 ]]; then
        return 1
    fi
    return 0
}

if ! preflight_check; then
    warn "pico-ice serial port not found — attempting USB recovery before first try ..."
    if "$RECOVER"; then
        sleep 2
    else
        err "USB recovery failed. Is the pico-ice plugged in?"
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# Main retry loop
# ---------------------------------------------------------------------------
MAX_ATTEMPTS=3
attempt=0

while [[ $attempt -lt $MAX_ATTEMPTS ]]; do
    attempt=$((attempt + 1))

    if [[ $attempt -gt 1 ]]; then
        log "Attempt $attempt/$MAX_ATTEMPTS ..."
        unmount_sda
    fi

    # Run mpremote with timeout.
    # Use SIGTERM first (gives mpremote time to close the USB serial port cleanly),
    # then SIGKILL after 5s if it hasn't exited.
    # This avoids leaving the RP2040 CDC endpoint in a half-open state.
    timeout --signal=TERM --kill-after=5 "$TIMEOUT" mpremote "$@"
    rc=$?

    if [[ $rc -eq 0 ]]; then
        ok "mpremote succeeded"
        exit 0
    elif [[ $rc -eq 124 ]]; then
        # 124 = SIGTERM timeout fired (--kill-after may escalate to 137)
        err "mpremote timed out after ${TIMEOUT}s (USB hang likely)"
    elif [[ $rc -eq 137 ]]; then
        # 137 = killed by SIGKILL (from --kill-after)
        err "mpremote killed (SIGKILL) after ${TIMEOUT}s + 5s grace period"
    else
        warn "mpremote exited with code $rc"
    fi

    # Don't try USB recovery on the last attempt (it won't help and wastes time)
    if [[ $attempt -lt $MAX_ATTEMPTS ]]; then
        log "Attempting USB recovery ..."
        if "$RECOVER"; then
            ok "USB recovered — retrying mpremote ..."
            sleep 2
        else
            warn "USB recovery failed — still retrying after short delay ..."
            sleep 5
        fi
    fi
done

err "All $MAX_ATTEMPTS attempts failed."
err "Run: fpga/host/usb_recover.sh"
err "Or unplug/replug the pico-ice USB cable."
exit 1
