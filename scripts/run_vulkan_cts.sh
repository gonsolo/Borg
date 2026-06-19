#!/usr/bin/env bash
# Run a slice of the Khronos Vulkan CTS (dEQP-VK) against the borgvk driver and
# report "passed N of <mandatory total>".
#
# borgvk is a narrow driver (it renders the one hand-compiled cube over serial to
# the FPGA), so the cases that pass are the query/setup ones that exercise
# borgvk's instance/device/object paths through the Mesa runtime without touching
# the serial→FPGA pipeline.  The default slice is the dEQP-VK.api.info.* class
# (device/format/limit enumeration) — a good audit of borgvk's reporting paths.
#
# Env overrides:
#   VK_GL_CTS     path to the VK-GL-CTS checkout (default: $HOME/src/VK-GL-CTS)
#   VK_CTS_CASE   case GLOB to run         (default: dEQP-VK.api.info.*)
#   VK_CTS_CASES  explicit newline list    (takes precedence over VK_CTS_CASE)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS}"
DEQP_VK="$VK_GL_CTS/build/external/vulkancts/modules/vulkan/deqp-vk"
ICD="$REPO/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"
SHIM="$REPO/mesa/build-borg/src/borg/drm/libborg_drm_shim.so"
VK_CTS_CASE="${VK_CTS_CASE:-dEQP-VK.api.info.*}"

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

# Selector: an explicit case list (VK_CTS_CASES) or a glob (VK_CTS_CASE).
if [[ -n "${VK_CTS_CASES:-}" ]]; then
  caselist="$(mktemp)"; trap 'rm -f "$caselist"' EXIT
  printf '%s\n' "$VK_CTS_CASES" > "$caselist"
  selector=(--deqp-caselist-file="$caselist")
  desc="$(grep -c '^dEQP-VK' "$caselist") explicit case(s)"
else
  selector=(--deqp-case="$VK_CTS_CASE")
  desc="$VK_CTS_CASE"
fi

echo "Running '$desc' against borgvk (a few minutes; full log: /tmp/borgvk-cts.log) ..."
cd "$(dirname "$DEQP_VK")"
# Send the per-case chatter to the log; we only surface the summary below.
LD_LIBRARY_PATH="${LOADER_DIR:+$LOADER_DIR:}${LD_LIBRARY_PATH:-}" \
VK_DRIVER_FILES="$ICD" \
LD_PRELOAD="${SHIM}${LD_PRELOAD:+:$LD_PRELOAD}" \
  ./deqp-vk "${selector[@]}" --deqp-log-filename=/tmp/borgvk-cts.qpa \
    > /tmp/borgvk-cts.log 2>&1 || true   # deqp-vk exits non-zero on expected fails

# Quiet output: just the run-totals block, then the headline.
sed -n '/Test run totals:/,/Waived:/p' /tmp/borgvk-cts.log || true

PASS="$(grep -oP 'Passed:\s+\K[0-9]+' /tmp/borgvk-cts.log | head -1 || echo 0)"
RAN="$(grep -oP 'Passed:\s+[0-9]+/\K[0-9]+' /tmp/borgvk-cts.log | head -1 || echo '?')"
# Percentage of the full mandatory suite (the ~1.6M conformance denominator).
PCT_ALL="$(awk -v p="${PASS:-0}" -v t="$TOTAL" 'BEGIN{ if (t+0>0) printf "%.3f", p*100.0/t; else printf "?" }')"
echo
echo "==> borgvk: passed ${PASS:-0} of ${TOTAL} mandatory Vulkan CTS tests = ${PCT_ALL}%"
