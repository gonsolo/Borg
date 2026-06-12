#!/usr/bin/env bash
# Measure Phase-B upload absorption on hardware. The instrumented firmware prints
# telemetry every 16 frames: G=valid geom pkts, X=valid tex pkts, R=distinct tex
# rows (of 64), F=checksum fails. We capture the firmware's UART TX while vkcube
# streams the real geometry/texture, then report the final counts.
#
# Run AFTER flashing the instrumented firmware and resetting the board.
set -u
PORT=/dev/ttyUSB0
LOG=/tmp/phaseb_uart.log
SECS="${1:-35}"

# Configure the line and start capturing the firmware's TX in the background.
stty -F "$PORT" 115200 raw -echo -echoe -echok -ixon 2>/dev/null
: > "$LOG"
cat "$PORT" > "$LOG" 2>/dev/null &
CATPID=$!

# Stream the real cube from Vulkan-Tools through the borgvk ICD.
LOADER=/nix/store/zs7y2aadk71bawprdcn000az9y05s8nf-vulkan-loader-1.4.341.0/lib
LIBX11=/nix/store/5m91jqg1526jzsahrgmd37k4ml3nc5l4-libx11-1.8.13/lib
LIBXCB=/nix/store/fc1g44pg3i10wfzh3gb4m54pfgclsn76-libxcb-1.17.0/lib
export LD_LIBRARY_PATH="$LOADER:$LIBX11:$LIBXCB:${LD_LIBRARY_PATH:-}"
export VK_DRIVER_FILES=/home/gonsolo/work/Borg/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json
timeout "$SECS" /home/gonsolo/work/Borg/Vulkan-Tools/build/cube/vkcube --wsi xlib >/dev/null 2>&1

sleep 1
kill "$CATPID" 2>/dev/null

echo "=== captured $(wc -l < "$LOG") telemetry lines ==="
echo "--- last 3 telemetry lines (G=geom X=tex R=distinct-rows/64 F=csum-fail) ---"
grep -oE 'G[0-9a-f]+ X[0-9a-f]+ R[0-9a-f]+ F[0-9a-f]+' "$LOG" | tail -3
echo "--- raw tail ---"
tail -2 "$LOG"
