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
#   VK_GL_CTS        path to the VK-GL-CTS checkout (default: $HOME/src/VK-GL-CTS)
#   VK_CTS_CASE      case GLOB to run              (default: dEQP-VK.api.info.*)
#   VK_CTS_CASES     explicit newline list         (takes precedence over VK_CTS_CASE)
#   BORGVK_SIM       path to arcilator_sim binary  — enables sim mode (no real HW)
#   BORGVK_SIM_FW    path to firmware.bin for arcilator_sim
#   BORGVK_PARALLEL  number of parallel deqp-vk instances
#                    (default: nproc when BORGVK_SIM is set, else 1)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS}"
DEQP_VK="$VK_GL_CTS/build/external/vulkancts/modules/vulkan/deqp-vk"
ICD="$REPO/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"
SHIM="$REPO/mesa/build-borg/src/borg/drm/libborg_drm_shim.so"
VK_CTS_CASE="${VK_CTS_CASE:-dEQP-VK.api.info.*}"

# Sim / parallel settings
BORGVK_SIM="${BORGVK_SIM:-}"
BORGVK_SIM_FW="${BORGVK_SIM_FW:-$REPO/software/borg/vkcube.bin}"
if [[ -n "$BORGVK_SIM" ]]; then
    BORGVK_PARALLEL="${BORGVK_PARALLEL:-$(nproc 2>/dev/null || echo 4)}"
else
    BORGVK_PARALLEL="${BORGVK_PARALLEL:-1}"
fi

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
if [[ -n "$BORGVK_SIM" && ! -x "$BORGVK_SIM" ]]; then
  echo "BORGVK_SIM binary not found or not executable: $BORGVK_SIM"
  echo "Build it:  make -C simulation/arcilator arcilator_sim"
  exit 1
fi

# Mandatory mustpass count = the conformance denominator.
MUSTPASS_DIR="$VK_GL_CTS/external/vulkancts/mustpass/main/vk-default"
TOTAL="$(cat "$MUSTPASS_DIR"/*.txt 2>/dev/null | grep -c '^dEQP-VK' || echo '?')"

# The Vulkan loader (libvulkan.so.1) dispatches to the ICD; deqp-vk dlopen()s it.
LOADER="$(find /nix/store -maxdepth 3 -name libvulkan.so.1 -path '*vulkan-loader*' 2>/dev/null | head -1)"
LOADER_DIR="$([[ -n "$LOADER" ]] && dirname "$LOADER" || true)"

# Common env for deqp-vk processes.
DEQP_ENV=(
    LD_LIBRARY_PATH="${LOADER_DIR:+$LOADER_DIR:}${LD_LIBRARY_PATH:-}"
    VK_DRIVER_FILES="$ICD"
    LD_PRELOAD="${SHIM}${LD_PRELOAD:+:$LD_PRELOAD}"
)
[[ -n "$BORGVK_SIM"    ]] && DEQP_ENV+=(BORGVK_SIM="$BORGVK_SIM")
[[ -n "$BORGVK_SIM_FW" ]] && DEQP_ENV+=(BORGVK_SIM_FW="$BORGVK_SIM_FW")

# ── Case list ─────────────────────────────────────────────────────────────────
# Produce $caselist_input: a newline-separated list of test names to run.
if [[ -n "${VK_CTS_CASES:-}" ]]; then
    caselist_input="$VK_CTS_CASES"
    desc="$(printf '%s\n' "$VK_CTS_CASES" | grep -c '^dEQP-VK') explicit case(s)"
elif [[ "$BORGVK_PARALLEL" -gt 1 ]]; then
    # Parallel mode: expand glob against mustpass so we can shard it.
    caselist_input="$(
        cat "$MUSTPASS_DIR"/*.txt 2>/dev/null | while IFS= read -r line; do
            [[ "$line" =~ ^dEQP-VK ]] || continue
            # bash glob pattern match (dEQP-VK.draw.* works correctly)
            [[ "$line" == $VK_CTS_CASE ]] && echo "$line"
        done
    )"
    desc="$(printf '%s\n' "$caselist_input" | grep -c '^dEQP-VK') case(s) matching $VK_CTS_CASE"
fi

# ── Sequential path (BORGVK_PARALLEL ≤ 1) ────────────────────────────────────
if [[ "${BORGVK_PARALLEL}" -le 1 ]]; then
    if [[ -n "${VK_CTS_CASES:-}" ]]; then
        caselist="$(mktemp)"; trap 'rm -f "$caselist"' EXIT
        printf '%s\n' "$VK_CTS_CASES" > "$caselist"
        selector=(--deqp-caselist-file="$caselist")
    else
        selector=(--deqp-case="$VK_CTS_CASE")
        desc="$VK_CTS_CASE"
    fi

    echo "Running '$desc' against borgvk (a few minutes; full log: /tmp/borgvk-cts.log) ..."
    cd "$(dirname "$DEQP_VK")"
    env "${DEQP_ENV[@]}" \
      ./deqp-vk "${selector[@]}" --deqp-log-filename=/tmp/borgvk-cts.qpa \
        > /tmp/borgvk-cts.log 2>&1 || true

    sed -n '/Test run totals:/,/Waived:/p' /tmp/borgvk-cts.log || true

    PASS="$(grep -oP 'Passed:\s+\K[0-9]+' /tmp/borgvk-cts.log | head -1 || echo 0)"
    PCT_ALL="$(awk -v p="${PASS:-0}" -v t="$TOTAL" 'BEGIN{ if (t+0>0) printf "%.3f", p*100.0/t; else printf "?" }')"
    echo
    echo "==> borgvk: passed ${PASS:-0} of ${TOTAL} mandatory Vulkan CTS tests = ${PCT_ALL}%"
    exit 0
fi

# ── Parallel path ─────────────────────────────────────────────────────────────
mapfile -t all_cases < <(printf '%s\n' "$caselist_input" | grep -v '^$' || true)
total_cases="${#all_cases[@]}"

if [[ "$total_cases" -eq 0 ]]; then
    echo "No cases matched '$VK_CTS_CASE' in mustpass; check VK_CTS_CASE or VK_CTS_CASES."
    exit 1
fi

# Clamp shards to available cases.
NSHARDS="$BORGVK_PARALLEL"
if [[ "$NSHARDS" -gt "$total_cases" ]]; then NSHARDS="$total_cases"; fi

shard_dir="$(mktemp -d)"
trap 'rm -rf "$shard_dir"' EXIT

# Split case list into NSHARDS roughly-equal files.
lines_per_shard=$(( (total_cases + NSHARDS - 1) / NSHARDS ))
printf '%s\n' "${all_cases[@]}" | split -l "$lines_per_shard" --suffix-length=4 - "$shard_dir/shard-"

shards=("$shard_dir"/shard-*)
actual_shards="${#shards[@]}"

echo "Running $total_cases case(s) across $actual_shards deqp-vk instance(s)${BORGVK_SIM:+ (arcilator sim)} ..."

pids=()
shard_logs=()
shard_qpas=()
idx=0
for shard in "${shards[@]}"; do
    n="$(printf '%04d' "$idx")"
    log="$shard_dir/deqp-$n.log"
    qpa="$shard_dir/cts-$n.qpa"
    shard_logs+=("$log")
    shard_qpas+=("$qpa")
    (
        cd "$(dirname "$DEQP_VK")"
        env "${DEQP_ENV[@]}" \
          ./deqp-vk --deqp-caselist-file="$shard" \
                    --deqp-log-filename="$qpa" \
                    > "$log" 2>&1 || true
    ) &
    pids+=($!)
    idx=$((idx + 1))
done

# Progress ticker: show running count.
echo -n "  waiting for ${#pids[@]} instance(s)"
for pid in "${pids[@]}"; do
    wait "$pid" || true
    echo -n "."
done
echo " done"

# Merge all shard logs into a single file for summary parsing.
cat "${shard_logs[@]}" > /tmp/borgvk-cts.log 2>/dev/null || true

# Aggregate pass/fail counts from per-shard log totals blocks.
PASS=0; FAIL=0; SKIP=0
for log in "${shard_logs[@]}"; do
    p="$(grep -oP 'Passed:\s+\K[0-9]+' "$log" | head -1 || echo 0)"
    f="$(grep -oP 'Failed:\s+\K[0-9]+' "$log" | head -1 || echo 0)"
    s="$(grep -oP 'Not supported:\s+\K[0-9]+' "$log" | head -1 || echo 0)"
    PASS=$((PASS + p))
    FAIL=$((FAIL + f))
    SKIP=$((SKIP + s))
done
RAN=$((PASS + FAIL + SKIP))

PCT_ALL="$(awk -v p="$PASS" -v t="$TOTAL" 'BEGIN{ if (t+0>0) printf "%.3f", p*100.0/t; else printf "?" }')"

echo
echo "Test run totals:"
echo "  Passed:        $PASS / $RAN"
echo "  Failed:        $FAIL / $RAN"
echo "  Not supported: $SKIP / $RAN"
echo
echo "==> borgvk: passed ${PASS} of ${TOTAL} mandatory Vulkan CTS tests = ${PCT_ALL}%"
