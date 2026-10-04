#!/usr/bin/env bash
# Run ONE Vulkan CTS case (dEQP-VK) against borgvk on the Arcilator simulator.
#
#   scripts/cts_one.sh <case-name>            # e.g. dEQP-VK.pipeline.monolithic.sampler.view_type.1d.format.b8g8r8_snorm.min_filter.linear
#   scripts/cts_one.sh 'dEQP-VK.pipeline.monolithic.sampler.view_type.1d.*'   # a pattern
#   scripts/cts_one.sh --list FILE            # run every case in FILE (one per line), one deqp-vk process
#
# Env overrides:
#   VK_GL_CTS    CTS checkout          (default $HOME/src/VK-GL-CTS, built with deqp-vk)
#   MESA_ROOT    mesa checkout         (default $HOME/work/Borg/mesa, with build-borg)
#   SIM_BIN      simulator binary      (default simulation/arcilator/arcilator_sim)
#   SIM_FW       simulator firmware    (default: built by this script into $OUT/kernel_sim.bin)
#   OUT          scratch dir           (default /tmp/borg-cts-one)
#   NO_FW_BUILD  set to reuse $SIM_FW without rebuilding it
#   DIRECT       set to use simulation/direct/direct_sim (Borg alone, no firmware) -- seconds, not minutes
#
# The firmware is built exactly like the simulators' Makefiles do (CLOCK_MHZ=25, UART at the
# sim baud) and copied away, so software/borg/kernel.bin is not reused for the board.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS}"
MESA_ROOT="${MESA_ROOT:-$HOME/work/Borg/mesa}"
SIM_BIN="${SIM_BIN:-$REPO/simulation/arcilator/arcilator_sim}"
DIRECT_BIN="$REPO/simulation/direct/direct_sim"
OUT="${OUT:-/tmp/borg-cts-one}"
SIM_FW="${SIM_FW:-$OUT/kernel_sim.bin}"
DEQP_DIR="$VK_GL_CTS/build/external/vulkancts/modules/vulkan"
ICD="$MESA_ROOT/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"
SHIM="$MESA_ROOT/build-borg/src/borg/drm/libborg_drm_shim.so"

[[ $# -ge 1 ]] || { sed -n 2,6p "$0"; exit 2; }
mkdir -p "$OUT"
if [[ "$1" == "--list" ]]; then
  cp "$2" "$OUT/cases.txt"
else
  printf '%s\n' "$1" > "$OUT/cases.txt"
fi

for f in "$DEQP_DIR/deqp-vk" "$ICD" "$SHIM" "$SIM_BIN"; do
  [[ -e "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done

if [[ -n "${DIRECT:-}" ]]; then
  [[ -x "$DIRECT_BIN" ]] || { echo "missing: $DIRECT_BIN (make -C simulation/direct)" >&2; exit 1; }
  export BORGVK_SIM_DIRECT="$DIRECT_BIN"
  NO_FW_BUILD=1
fi

if [[ -z "${NO_FW_BUILD:-}" ]]; then
  baud=$(sed -n 's/^#define SIM_UART_BAUD \([0-9]*\).*/\1/p' "$REPO/simulation/common/common_sim.h")
  make -C "$REPO/software/borg" clean >/dev/null 2>&1 || true
  make -C "$REPO/software/borg" CLOCK_MHZ=25 EXTRA_CFLAGS="-DBORG_UART_BAUD=$baud" kernel >"$OUT/fw_build.log" 2>&1 \
    || { echo "firmware build failed, see $OUT/fw_build.log" >&2; exit 1; }
  cp "$REPO/software/borg/kernel.bin" "$SIM_FW"
fi

LOADER="$(pkg-config --variable=libdir vulkan)"
export LD_LIBRARY_PATH="$LOADER:${LD_LIBRARY_PATH:-}"
export VK_DRIVER_FILES="$ICD"
export VK_LOADER_LAYERS_DISABLE="~implicit~"
export LD_PRELOAD="$SHIM${LD_PRELOAD:+:$LD_PRELOAD}"
export BORGVK_SIM="$SIM_BIN"
export BORGVK_SIM_FW="$SIM_FW"

cd "$DEQP_DIR"
start=$(date +%s)
if [[ "$1" == "--list" ]]; then
  sel=(--deqp-caselist-file="$OUT/cases.txt")
else
  sel=(--deqp-case="$1")      # a name, or a pattern such as 'dEQP-VK.pipeline.monolithic.sampler.view_type.1d.*'
fi
./deqp-vk "${sel[@]}" --deqp-log-filename="$OUT/result.qpa" \
  > "$OUT/deqp.log" 2>&1 || true
elapsed=$(( $(date +%s) - start ))

grep -E "^\s+(Pass|Fail|NotSupported|QualityWarning|CompatibilityWarning)|Test case|Passed:|Failed:|Not supported:" "$OUT/deqp.log" || true
echo "elapsed ${elapsed}s  (log: $OUT/deqp.log, qpa: $OUT/result.qpa)"
