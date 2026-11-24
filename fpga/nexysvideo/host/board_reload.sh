#!/usr/bin/env bash
# Load a bitstream again after a hung CTS chunk: cts_par.sh runs it as BOARD_RESET.
#   board_reload.sh <bitstream>
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pkill -x borg_ftdi; sleep 1; pkill -9 -x borg_ftdi
# borg_ftdi leaves channel A's FIFO mode on exit; a channel A in that mode locks openFPGALoader out.
timeout 10 "$here/borg_ftdi" < /dev/null > /dev/null
for try in 1 2 3; do
  timeout 120 openFPGALoader -b nexysVideo "$1" > /dev/null 2>&1 && { echo "$(date +%T) reloaded"; exit 0; }
  sleep 5
done
echo "$(date +%T) reload failed"; exit 1
