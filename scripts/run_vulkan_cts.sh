#!/usr/bin/env bash
# Run a slice of the Khronos Vulkan CTS (dEQP-VK) against the borgvk driver and
# report "passed N of <mandatory total>".
#
# borgvk is a narrow driver (it renders the one hand-compiled cube over serial to
# the FPGA), so the only CTS cases that survive are setup-only tests that create
# Vulkan objects but never render — they exercise borgvk's instance/device/object
# paths through the Mesa runtime without touching the serial→FPGA pipeline.
#
# Env overrides:
#   VK_GL_CTS     path to the VK-GL-CTS checkout (default: $HOME/src/VK-GL-CTS)
#   VK_CTS_CASES  newline-separated case list (default: the survivor set below)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS}"
DEQP_VK="$VK_GL_CTS/build/external/vulkancts/modules/vulkan/deqp-vk"
ICD="$REPO/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"
SHIM="$REPO/mesa/build-borg/src/borg/drm/libborg_drm_shim.so"

# Survivor set: object creation only, no rendering / no queue submit / no serial.
CASES="${VK_CTS_CASES:-dEQP-VK.api.device_init.create_device.basic
dEQP-VK.api.smoke.create_sampler
dEQP-VK.api.smoke.create_shader}"

if [[ ! -x "$DEQP_VK" ]]; then
  echo "deqp-vk not built at: $DEQP_VK"
  echo "Build it (native gcc + headless target):"
  echo "  cd $VK_GL_CTS && python3 external/fetch_sources.py"
  echo "  CC=gcc CXX=g++ cmake -S . -B build -GNinja -DCMAKE_BUILD_TYPE=Release \\"
  echo "       -DDEQP_TARGET=vulkan_headless -DSELECTED_BUILD_TARGETS=deqp-vk"
  echo "  cmake --build build --target deqp-vk"
  echo "(override the checkout location with VK_GL_CTS=...)"
  exit 1
fi
if [[ ! -f "$ICD" ]]; then
  echo "borgvk ICD missing: $ICD"
  echo "Build the Mesa driver first:  make -C software/mesa"
  exit 1
fi

# Mandatory mustpass count = the conformance denominator.
MUSTPASS_DIR="$VK_GL_CTS/external/vulkancts/mustpass/main/vk-default"
TOTAL="$(cat "$MUSTPASS_DIR"/*.txt 2>/dev/null | grep -c '^dEQP-VK' || echo '?')"

# The Vulkan loader (libvulkan.so.1) dispatches to the ICD; deqp-vk dlopen()s it.
LOADER="$(find /nix/store -maxdepth 3 -name libvulkan.so.1 -path '*vulkan-loader*' 2>/dev/null | head -1)"
LOADER_DIR="$([[ -n "$LOADER" ]] && dirname "$LOADER" || true)"

caselist="$(mktemp)"; trap 'rm -f "$caselist"' EXIT
printf '%s\n' "$CASES" > "$caselist"
RAN="$(grep -c '^dEQP-VK' "$caselist")"

echo "Running $RAN survivor case(s) against borgvk ..."
cd "$(dirname "$DEQP_VK")"
LD_LIBRARY_PATH="${LOADER_DIR:+$LOADER_DIR:}${LD_LIBRARY_PATH:-}" \
VK_DRIVER_FILES="$ICD" \
LD_PRELOAD="${SHIM}${LD_PRELOAD:+:$LD_PRELOAD}" \
  ./deqp-vk --deqp-caselist-file="$caselist" --deqp-log-filename=/tmp/borgvk-cts.qpa \
    2>&1 | tee /tmp/borgvk-cts.log

PASS="$(grep -oP 'Passed:\s+\K[0-9]+' /tmp/borgvk-cts.log | head -1 || echo 0)"
echo
echo "==> borgvk: passed ${PASS:-0} of ${TOTAL} mandatory Vulkan CTS tests (ran ${RAN})"
