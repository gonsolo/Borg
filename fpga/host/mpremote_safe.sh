#!/usr/bin/env bash
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# mpremote_safe.sh — Wrapper around mpremote that handles USB hangs.
#
# Runs mpremote with a timeout. If it hangs (times out), automatically
# attempts USB recovery so you don't have to reboot.
#
# Usage:
#   ./mpremote_safe.sh [timeout_secs] <mpremote_args...>
#
# Examples:
#   ./mpremote_safe.sh 120 mount . + run host/program_bitstream.py
#   ./mpremote_safe.sh 60  cp borg_mmio.py :borg_mmio.py
#
# The first argument is the timeout in seconds. If omitted, defaults to 120s.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECOVER="$SCRIPT_DIR/usb_recover.sh"

BOLD='\033[1m'
RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RESET='\033[0m'

# Parse optional timeout (first arg if numeric)
TIMEOUT=120
if [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; then
    TIMEOUT="$1"
    shift
fi

if [[ $# -eq 0 ]]; then
    echo "Usage: $0 [timeout_secs] <mpremote args...>"
    exit 1
fi

MAX_ATTEMPTS=3
attempt=0

while [[ $attempt -lt $MAX_ATTEMPTS ]]; do
    attempt=$((attempt + 1))

    if [[ $attempt -gt 1 ]]; then
        echo -e "${YELLOW}[SAFE] Attempt $attempt/$MAX_ATTEMPTS ...${RESET}"
    fi

    # Run mpremote with timeout
    timeout --signal=KILL "$TIMEOUT" mpremote "$@"
    rc=$?

    if [[ $rc -eq 0 ]]; then
        # Success
        exit 0
    elif [[ $rc -eq 137 ]]; then
        # 137 = killed by SIGKILL (timeout fired)
        echo -e "${RED}[SAFE] mpremote timed out after ${TIMEOUT}s (USB hang likely)${RESET}"
    else
        # mpremote failed for another reason — might be a transient USB glitch
        echo -e "${YELLOW}[SAFE] mpremote exited with code $rc${RESET}"
    fi

    # Don't try USB recovery on the last attempt
    if [[ $attempt -lt $MAX_ATTEMPTS ]]; then
        echo -e "${BOLD}[SAFE] Attempting USB recovery ...${RESET}"
        if "$RECOVER"; then
            echo -e "${GREEN}[SAFE] USB recovered, retrying mpremote ...${RESET}"
            sleep 2
        else
            echo -e "${RED}[SAFE] USB recovery failed${RESET}"
            # Still try again — sometimes just a small delay helps
            sleep 5
        fi
    fi
done

echo -e "${RED}[SAFE] All $MAX_ATTEMPTS attempts failed.${RESET}"
echo -e "${RED}[SAFE] Run: fpga/host/usb_recover.sh${RESET}"
echo -e "${RED}[SAFE] Or unplug/replug the pico-ice.${RESET}"
exit 1
