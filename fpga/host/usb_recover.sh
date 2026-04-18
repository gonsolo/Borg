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
#   1. Kills any stuck mpremote/tio processes
#   2. Uses the kernel's USB "authorized" sysfs toggle to force a
#      disconnect/reconnect cycle (no sudo needed if udev rules allow)
#   3. Falls back to usbreset(1) if the sysfs method doesn't work
#   4. Waits for /dev/ttyACM0 to reappear
#
# Usage:
#   ./usb_recover.sh          # Auto-detect and recover
#   ./usb_recover.sh --check  # Just check if pico-ice is responsive

set -euo pipefail

BOLD='\033[1m'
RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RESET='\033[0m'

PICO_VID="1209"
PICO_PID="b1c0"
PICO_VIDPID="${PICO_VID}:${PICO_PID}"
MAX_WAIT_SECS=10

log()  { echo -e "${BOLD}[USB]${RESET} $*"; }
ok()   { echo -e "${GREEN}[USB] ✓${RESET} $*"; }
warn() { echo -e "${YELLOW}[USB] ⚠${RESET} $*"; }
err()  { echo -e "${RED}[USB] ✗${RESET} $*"; }

# Find the sysfs path for the pico-ice RP2040
find_usb_sysfs() {
    for d in /sys/bus/usb/devices/[0-9]-[0-9]*/; do
        local vid pid
        vid=$(cat "$d/idVendor" 2>/dev/null || true)
        pid=$(cat "$d/idProduct" 2>/dev/null || true)
        if [[ "$vid" == "$PICO_VID" && "$pid" == "$PICO_PID" ]]; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

# Check if pico-ice ttyACM is present and mpremote can talk to it
check_responsive() {
    if [[ ! -e /dev/ttyACM0 ]]; then
        return 1
    fi
    # Quick mpremote probe — 3s timeout
    if timeout 3 mpremote eval "1+1" &>/dev/null; then
        return 0
    fi
    return 1
}

# Kill stuck mpremote / tio processes
kill_stuck() {
    local killed=0
    for proc in mpremote tio; do
        if pkill -f "$proc" 2>/dev/null; then
            log "Killed stuck $proc process"
            killed=1
        fi
    done
    if [[ $killed -eq 1 ]]; then
        sleep 1
    fi
}

# Method 1: sysfs authorized toggle (no root needed if permissions allow)
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

# Method 2: usbreset by VID:PID
recover_usbreset() {
    if ! command -v usbreset &>/dev/null; then
        warn "usbreset not found"
        return 1
    fi
    log "Running usbreset ${PICO_VIDPID} ..."
    if usbreset "${PICO_VIDPID}" 2>&1; then
        return 0
    fi
    # usbreset may need sudo
    warn "usbreset failed, trying with sudo ..."
    if sudo usbreset "${PICO_VIDPID}" 2>&1; then
        return 0
    fi
    return 1
}

# Method 3: Unbind/rebind the USB driver (nuclear option, needs root)
recover_unbind() {
    local sysfs_path
    sysfs_path=$(find_usb_sysfs) || return 1
    local devname
    devname=$(basename "$sysfs_path")

    log "Unbinding USB device $devname from driver ..."
    if echo "$devname" | sudo tee /sys/bus/usb/drivers/usb/unbind &>/dev/null; then
        sleep 1
        log "Rebinding USB device $devname ..."
        echo "$devname" | sudo tee /sys/bus/usb/drivers/usb/bind &>/dev/null
        return 0
    fi
    return 1
}

# Wait for ttyACM0 to reappear
wait_for_tty() {
    log "Waiting for /dev/ttyACM0 ..."
    for i in $(seq 1 "$MAX_WAIT_SECS"); do
        if [[ -e /dev/ttyACM0 ]]; then
            ok "/dev/ttyACM0 reappeared after ${i}s"
            return 0
        fi
        sleep 1
    done
    err "/dev/ttyACM0 did not reappear after ${MAX_WAIT_SECS}s"
    return 1
}

# --- Main ---

if [[ "${1:-}" == "--check" ]]; then
    if check_responsive; then
        ok "pico-ice is responsive on /dev/ttyACM0"
        exit 0
    else
        err "pico-ice is NOT responsive"
        exit 1
    fi
fi

log "Recovering pico-ice USB (${PICO_VIDPID}) ..."

# Step 1: Kill stuck processes
kill_stuck

# Step 2: Check if it's already fine after killing processes
if check_responsive; then
    ok "pico-ice recovered after killing stuck processes"
    exit 0
fi

# Step 3: Try recovery methods in order of invasiveness
for method in recover_usbreset recover_sysfs recover_unbind; do
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
