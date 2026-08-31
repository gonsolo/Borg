#!/usr/bin/env bash
# Run the unmodified Khronos Vulkan-Tools vkcube on the host, rendering on the
# ULX3S over serial via the borgvk native ICD. The host opens a Wayland window
# (its content is unused); each frame's MVP is shipped to the firmware, which
# renders the cube on the HDMI monitor.
#
# Usage: ./run-vkcube.sh [seconds]   (default: run until Ctrl-C)
set -euo pipefail
REPO=/home/gonsolo/work/Borg

# Runtime libs the dynamically-linked / dlopen'd bits need (not in rpath).
# Resolved via pkg-config at run time rather than hardcoded store paths --
# a hardcoded /nix/store/<hash>-... here goes stale the moment `nix store gc`
# reclaims a generation this script wasn't holding a live reference to (hit
# this for real: 2026-08-31, gc during a devshell rebuild wiped the paths
# that used to be hardcoded here, silently breaking this script until
# someone tried to run it and got "file not found").
LOADER=$(pkg-config --variable=libdir vulkan)
LIBWAYLAND=$(pkg-config --variable=libdir wayland-client)
export LD_LIBRARY_PATH="$LOADER:$LIBWAYLAND:${LD_LIBRARY_PATH:-}"

# Point the loader at the in-tree (build-tree) borgvk driver, not the installed one:
export VK_DRIVER_FILES="$REPO/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"

# Preload the Borg DRM shim if it has been built. With the shim, all GEM buffer
# allocations and submit ioctls go through the shim (which manages serial
# transport). Without it the driver falls back to malloc+serial (legacy path).
BORG_DRM_SHIM="$REPO/mesa/build-borg/src/borg/drm/libborg_drm_shim.so"
if [[ -f "$BORG_DRM_SHIM" ]]; then
  export LD_PRELOAD="${LD_PRELOAD:-}${LD_PRELOAD:+:}$BORG_DRM_SHIM"
fi

# Steady frame pacing: cube.c spins a CONSTANT angle per frame, so without this
# the host emits ~60 constant-angle MVPs/sec while the FPGA shows ~15 — an
# irregular subsample that makes the rotation judder.  Holding each host frame to
# a fixed period at/below the FPGA's sustained rate makes every shown frame
# advance by exactly one angle step (smooth).  ~75 ms ≈ 13 fps, just under the
# ~15 fps render rate.  Override or unset (free-run) as desired.
export BORGVK_FRAME_MS="${BORGVK_FRAME_MS:-75}"

VKCUBE="$REPO/Vulkan-Tools/build/cube/vkcube"
if [[ $# -ge 1 ]]; then
  exec timeout "$1" "$VKCUBE" --wsi wayland
else
  exec "$VKCUBE" --wsi wayland
fi
