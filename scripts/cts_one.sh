#!/usr/bin/env bash
# Run ONE Vulkan CTS case (dEQP-VK) against borgvk on the direct simulator (Borg alone).
#
#   scripts/cts_one.sh <case-name>            # e.g. dEQP-VK.pipeline.monolithic.sampler.view_type.1d.format.b8g8r8_snorm.min_filter.linear
#   scripts/cts_one.sh 'dEQP-VK.pipeline.monolithic.sampler.view_type.1d.*'   # a pattern
#   scripts/cts_one.sh --list FILE            # run every case in FILE (one per line), one deqp-vk process
#
# Env overrides:
#   VK_GL_CTS    CTS checkout          (default $HOME/src/VK-GL-CTS-1.4.6.2 built with deqp-vk)
#   MESA_ROOT    mesa checkout         (default $HOME/work/Borg/mesa, with build-borg)
#   OUT          scratch dir           (default /tmp/borg-cts-one)
#   DIRECT_BIN   the simulator (default simulation/direct/direct_sim; direct_sim_v for BORG_TRACE)
#   BORG_HW_RAW  instead the device path: a program speaking the simulator's protocol with no shared
#                memory (simulation/direct/link_sim, fpga/nexysvideo/host/borg_ftdi)
#   DEQP_ARGS    extra deqp-vk options
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS-1.4.6.2}"
MESA_ROOT="${MESA_ROOT:-$HOME/work/Borg/mesa}"
DIRECT_BIN="${DIRECT_BIN:-$REPO/simulation/direct/direct_sim}"
OUT="${OUT:-/tmp/borg-cts-one}"
DEQP_DIR="$VK_GL_CTS/build/external/vulkancts/modules/vulkan"
ICD="$MESA_ROOT/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"
SHIM="$MESA_ROOT/build-borg/src/borg/drm/libborg_drm_shim.so"

[[ $# -ge 1 ]] || { sed -n 2,6p "$0"; exit 2; }
mkdir -p "$OUT"
if [[ "$1" == "--list" ]]; then
  if [[ -z "${CTS_PAR_WORKER:-}" && $(grep -c . "$2") -gt 2 ]]; then
    echo "cts_one.sh runs a list serially; use: CASELIST=$2 JOBS=12 scripts/cts_par.sh <label>" >&2; exit 2
  fi
  cp "$2" "$OUT/cases.txt"
else
  printf '%s\n' "$1" > "$OUT/cases.txt"
fi

for f in "$DEQP_DIR/deqp-vk" "$ICD" "$SHIM"; do
  [[ -e "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done
if [[ -n "${BORG_HW_RAW:-}" ]]; then
  export BORGVK_HW=1
else
  [[ -x "$DIRECT_BIN" ]] || { echo "missing: $DIRECT_BIN (make -C simulation/direct)" >&2; exit 1; }
  export BORGVK_SIM_DIRECT="$DIRECT_BIN"
fi

LOADER="$(pkg-config --variable=libdir vulkan)"
export LD_LIBRARY_PATH="$LOADER:${LD_LIBRARY_PATH:-}"
export VK_DRIVER_FILES="$ICD"
export VK_LOADER_LAYERS_DISABLE="~implicit~"

cd "$DEQP_DIR"
start=$(date +%s)
if [[ "$1" == "--list" ]]; then
  sel=(--deqp-caselist-file="$OUT/cases.txt")
else
  sel=(--deqp-case="$1")      # a name, or a pattern such as 'dEQP-VK.pipeline.monolithic.sampler.view_type.1d.*'
fi
LD_PRELOAD="$SHIM${LD_PRELOAD:+:$LD_PRELOAD}" ./deqp-vk "${sel[@]}" ${DEQP_ARGS:-} --deqp-log-filename="$OUT/result.qpa" \
  > "$OUT/deqp.log" 2>&1 || true
elapsed=$(( $(date +%s) - start ))

grep -E "^\s+(Pass|Fail|NotSupported|QualityWarning|CompatibilityWarning)|Test case|Passed:|Failed:|Not supported:" "$OUT/deqp.log" || true
echo "elapsed ${elapsed}s  (log: $OUT/deqp.log, qpa: $OUT/result.qpa)"
