#!/usr/bin/env bash
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# usb_recover.sh — Recover a hung pico-ice RP2040 USB without rebooting.
#
# The pico-ice RP2040 (MicroPython, VID:PID 1209:b1c0) sometimes hangs its
# USB CDC endpoint during long SPI flash writes or FPGA boot sequences.
# When that happens, mpremote hangs and the ttyACM device becomes unusable.
#
# This script:
#   1. Kills any stuck mpremote/tio processes (safely — exact match only)
#   2. Uses the kernel's USB "authorized" sysfs toggle to force a
#      disconnect/reconnect cycle (no sudo needed if udev rules allow)
#   3. Falls back to unbind/rebind if the sysfs method doesn't work
#   4. Waits for /dev/ttyACM0 to reappear
#
# Usage:
#   ./usb_recover.sh          # Auto-detect and recover
#   ./usb_recover.sh --check  # Just check if pico-ice is responsive
#
# SAFETY:
#   Run this script detached from your terminal to avoid killing it on USB
#   disruption:
#     nohup ./usb_recover.sh &>/tmp/usb_recover.log &
#   Or wrap in setsid:
#     setsid ./usb_recover.sh &>/tmp/usb_recover.log &

# ---------------------------------------------------------------------------
# Detach from the calling terminal's process group so that a SIGHUP caused
# by a USB-triggered terminal disconnect cannot reach us or our caller.
# Only re-exec if we are still the process group leader of the terminal.
# ---------------------------------------------------------------------------
if [[ -z "${USB_RECOVER_DETACHED:-}" ]]; then
    export USB_RECOVER_DETACHED=1
    exec setsid bash "$0" "$@"
fi

# ---------------------------------------------------------------------------
# Strict mode — but we catch errors explicitly where needed.
# NOTE: `set -e` is intentionally NOT used globally here because several
# recovery steps are expected to fail and we want to try all of them.
# ---------------------------------------------------------------------------
set -uo pipefail

BOLD='\033[1m'
RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RESET='\033[0m'

PICO_VID="1209"
PICO_PID="b1c0"
GENERIC_VID="2e8a"
GENERIC_PID="0005"
MAX_WAIT_SECS=10

log()  { echo -e "${BOLD}[USB]${RESET} $*"; }
ok()   { echo -e "${GREEN}[USB] ✓${RESET} $*"; }
warn() { echo -e "${YELLOW}[USB] ⚠${RESET} $*"; }
err()  { echo -e "${RED}[USB] ✗${RESET} $*"; }

# ---------------------------------------------------------------------------
# Find the sysfs path for the pico-ice RP2040
# ---------------------------------------------------------------------------
find_usb_sysfs() {
    for d in /sys/bus/usb/devices/[0-9]-[0-9]*/; do
        local vid pid
        vid=$(cat "$d/idVendor" 2>/dev/null || true)
        pid=$(cat "$d/idProduct" 2>/dev/null || true)
        if [[ ("$vid" == "$PICO_VID" && "$pid" == "$PICO_PID") || \
              ("$vid" == "$GENERIC_VID" && "$pid" == "$GENERIC_PID") ]]; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

# ---------------------------------------------------------------------------
# Check if pico-ice ttyACM is present and mpremote can talk to it
# ---------------------------------------------------------------------------
check_responsive() {
    local pico_tty
    pico_tty=$(ls /dev/serial/by-id/usb-*pico-ice*-if00* 2>/dev/null | head -n1 || echo "/dev/ttyACM0")
    if [[ ! -e "$pico_tty" ]]; then
        return 1
    fi
    # Quick mpremote probe — 3s timeout
    if timeout 3 mpremote connect "$pico_tty" eval "1+1" &>/dev/null; then
        return 0
    fi
    return 1
}

# ---------------------------------------------------------------------------
# Kill stuck mpremote / tio processes.
#
# SAFETY: We use `pkill -x` (exact command name match) instead of `pkill -f`
# (full command-line match) to avoid accidentally killing processes that
# merely have "tio" or "mpremote" as a substring in their argv (e.g. the
# terminal emulator, ratio, options, etc.).
#
# We also restrict to processes owned by the current user only (-u $USER).
# ---------------------------------------------------------------------------
kill_stuck() {
    local killed=0
    local our_pid=$$
    local our_sid
    our_sid=$(ps -o sid= -p $$ | tr -d ' ')

    for proc in mpremote tio; do
        # Collect PIDs: exact name match, current user, not ourselves or our session
        local pids
        pids=$(pgrep -x -u "$USER" "$proc" 2>/dev/null || true)
        for pid in $pids; do
            # Skip if it's us
            [[ "$pid" == "$our_pid" ]] && continue
            # Skip if it's in our own session (shouldn't be, but be defensive)
            local pid_sid
            pid_sid=$(ps -o sid= -p "$pid" 2>/dev/null | tr -d ' ' || true)
            [[ "$pid_sid" == "$our_sid" ]] && continue

            log "Killing stuck $proc (PID $pid) ..."
            kill "$pid" 2>/dev/null && killed=1 || true
        done
    done

    if [[ $killed -eq 1 ]]; then
        sleep 1
    fi
}

# ---------------------------------------------------------------------------
# Method 1: sysfs authorized toggle (no root needed if udev rules allow)
# ---------------------------------------------------------------------------
recover_sysfs() {
    local sysfs_path
    sysfs_path=$(find_usb_sysfs) || return 1
    local auth_file="${sysfs_path}authorized"

    if [[ ! -w "$auth_file" ]]; then
        warn "Cannot write to $auth_file (need udev rule or sudo)"
        return 1
    fi

    log "Deauthorizing USB device at $sysfs_path ..."
    echo 0 > "$auth_file"
    sleep 1
    log "Re-authorizing USB device ..."
    echo 1 > "$auth_file"
    return 0
}

# usbreset intentionally removed: it triggers the kernel USB reset cascade
# (repeated error -110 / EPROTO) and causes usb-storage to deadlock in D state.
# Use authorized toggle or unbind/rebind instead.

# ---------------------------------------------------------------------------
# Method 2: Unbind/rebind the USB driver (nuclear option, needs root)
#
# SAFETY: We check that sudo is available and non-interactive before using it.
# If sudo would prompt for a password, we skip this method rather than hang.
# ---------------------------------------------------------------------------
recover_unbind() {
    local sysfs_path
    sysfs_path=$(find_usb_sysfs) || return 1
    local devname
    devname=$(basename "$sysfs_path")

    # Check sudo is usable without a password prompt
    if ! sudo -n true 2>/dev/null; then
        warn "sudo requires a password — skipping unbind/rebind (add NOPASSWD to sudoers)"
        return 1
    fi

    log "Unbinding USB device $devname from driver ..."
    if echo "$devname" | sudo tee /sys/bus/usb/drivers/usb/unbind &>/dev/null; then
        sleep 1
        log "Rebinding USB device $devname ..."
        echo "$devname" | sudo tee /sys/bus/usb/drivers/usb/bind &>/dev/null || true
        return 0
    fi
    return 1
}

# ---------------------------------------------------------------------------
# Wait for ttyACM0 to reappear
# ---------------------------------------------------------------------------
wait_for_tty() {
    local pico_tty
    pico_tty=$(ls /dev/serial/by-id/usb-*pico-ice*-if00* 2>/dev/null | head -n1 || echo "/dev/ttyACM0")
    log "Waiting for $pico_tty ..."
    for i in $(seq 1 "$MAX_WAIT_SECS"); do
        if [[ -e "$pico_tty" ]]; then
            ok "$pico_tty reappeared after ${i}s"
            return 0
        fi
        sleep 1
    done
    err "$pico_tty did not reappear after ${MAX_WAIT_SECS}s"
    return 1
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if [[ "${1:-}" == "--check" ]]; then
    if check_responsive; then
        ok "pico-ice is responsive on the serial port"
        exit 0
    else
        err "pico-ice is NOT responsive"
        exit 1
    fi
fi

log "Recovering pico-ice USB (${PICO_VID}:${PICO_PID} or ${GENERIC_VID}:${GENERIC_PID}) ..."

# Step 1: Kill stuck processes (safe, exact-match only)
kill_stuck

# Step 2: Check if it's already fine after killing stuck processes
if check_responsive; then
    ok "pico-ice recovered after killing stuck processes"
    exit 0
fi

# Step 3: Try recovery methods in order of invasiveness
for method in recover_sysfs recover_unbind; do
    log "Trying: $method ..."
    if $method; then
        sleep 2  # Give USB stack time to re-enumerate
        if wait_for_tty && check_responsive; then
            ok "pico-ice recovered via $method"
            exit 0
        fi
        warn "$method completed but device not responsive yet"
    else
        warn "$method failed, trying next method ..."
    fi
done

# Step 4: Last resort — suggest physical replug
err "All USB recovery methods failed."
err "Please unplug and replug the pico-ice USB cable."
err "If that doesn't help either, a reboot is needed."
exit 1
