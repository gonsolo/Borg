#!/usr/bin/env bash
# Run the unmodified Khronos Vulkan-Tools vkcube on the host, rendering on the
# ULX3S over serial via the borgvk native ICD. The host opens an Xlib window
# (its content is unused); each frame's MVP is shipped to the firmware, which
# renders the cube on the HDMI monitor.
#
# Usage: ./run-vkcube.sh [seconds]   (default: run until Ctrl-C)
set -euo pipefail
REPO=/home/gonsolo/work/Borg

# Runtime libs the dynamically-linked / dlopen'd bits need (not in rpath):
LOADER=/nix/store/zs7y2aadk71bawprdcn000az9y05s8nf-vulkan-loader-1.4.341.0/lib
LIBX11=/nix/store/5m91jqg1526jzsahrgmd37k4ml3nc5l4-libx11-1.8.13/lib
LIBXCB=/nix/store/fc1g44pg3i10wfzh3gb4m54pfgclsn76-libxcb-1.17.0/lib
export LD_LIBRARY_PATH="$LOADER:$LIBX11:$LIBXCB:${LD_LIBRARY_PATH:-}"

# Point the loader at the in-tree (build-tree) borgvk driver, not the installed one:
export VK_DRIVER_FILES="$REPO/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"

VKCUBE="$REPO/Vulkan-Tools/build/cube/vkcube"
if [[ $# -ge 1 ]]; then
  exec timeout "$1" "$VKCUBE" --wsi xlib
else
  exec "$VKCUBE" --wsi xlib
fi
