#!/usr/bin/env bash
# The unmodified Khronos vkcube (Vulkan-Tools/cube) on borgvk and the simulated GPU, in an X
# window: direct_sim is the arcilator model, DIRECT_BIN=simulation/direct/direct_sim_v the
# Verilator one. Without a display it runs in Xvfb. BORGVK_DUMP_PPM=f.ppm writes each frame.
#   scripts/vkcube_host.sh [vkcube args]          e.g. --c 100 --width 256 --height 256
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${DISPLAY:-}" ]]; then
  exec xvfb-run -a -s "-screen 0 640x480x24" "$0" "$@"
fi
libdir() { pkg-config --variable=libdir "$1"; }
export LD_LIBRARY_PATH="$(libdir vulkan):$(libdir wayland-client):$(libdir libdrm):$(libdir xcb):$(libdir x11-xcb):$(libdir xshmfence):$(libdir xcb-keysyms):$(libdir expat):$(libdir libzstd):$(dirname "$(g++ -print-file-name=libstdc++.so.6)")"
export VK_DRIVER_FILES="$REPO/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"
export BORGVK_SIM_DIRECT="${DIRECT_BIN:-$REPO/simulation/direct/direct_sim}"
exec "$REPO/Vulkan-Tools/build/cube/vkcube" --wsi xcb --suppress_popups "$@"
