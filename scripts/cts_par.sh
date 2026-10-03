#!/usr/bin/env bash
# Run a pattern of Vulkan CTS cases sharded over N parallel deqp-vk processes.
#
#   JOBS=12 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.pipeline.monolithic.sampler.view_type.1d.*'
#
# MUSTPASS=1.0.2.6 restricts the run to the cases of that Khronos CTS release's mustpass
# list (the Vulkan 1.0 conformance set), mapped to today's case names; without it every
# case the current CTS has under the pattern runs, extension variants included.
#
# MUSTPASS_NATIVE=1 with VK_GL_CTS pointing at a checkout of that same release (e.g.
# ~/src/VK-GL-CTS-1.0.2.6): the names are used as listed, no mapping or intersection.
#
# Each shard is a separate scripts/cts_one.sh --list run with its own scratch dir
# ($OUT/run_N); the summary and a per-case result table land in $OUT/summary.txt
# and $OUT/results.txt.  Same env overrides as cts_one.sh (DIRECT, VK_GL_CTS,
# MESA_ROOT ...); OUT here is the parent directory.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS}"
OUT="${OUT:-/tmp/borg-cts-par}"
JOBS="${JOBS:-$(nproc)}"
DEQP_DIR="$VK_GL_CTS/build/external/vulkancts/modules/vulkan"
[[ $# -eq 1 ]] || { sed -n 2,6p "$0"; exit 2; }
rm -rf "$OUT"; mkdir -p "$OUT"

if [[ -n "${MUSTPASS_NATIVE:-}" ]]; then
  # VK_GL_CTS is a checkout of the very release whose mustpass list is wanted: use the
  # names as listed.
  rx="^$(printf '%s' "$1" | sed -e 's/[.]/\\./g' -e 's/[*]/.*/g')\$"
  ver="$(ls "$VK_GL_CTS"/external/vulkancts/mustpass | grep -E '^[0-9]' | sort -V | tail -1)"
  git -C "$VK_GL_CTS" show "HEAD:external/vulkancts/mustpass/$ver/vk-default.txt" \
    | grep -E "$rx" | sort -u > "$OUT/all.txt" || true
else
  # Expand the pattern to case names (deqp writes <archive>-cases.txt in its cwd).
  ( cd "$OUT" && "$DEQP_DIR/deqp-vk" --deqp-case="$1" --deqp-runmode=txt-caselist \
      --deqp-archive-dir="$DEQP_DIR" >/dev/null 2>&1 ) || true
  LIST="$(ls "$OUT"/*cases.txt 2>/dev/null | head -1 || true)"
  [[ -n "$LIST" ]] || { echo "could not expand '$1'" >&2; exit 1; }
  sed -n 's/^TEST: //p' "$LIST" | grep -E '^dEQP-VK\.' > "$OUT/all.txt" || true
fi
if [[ -n "${MUSTPASS:-}" && -z "${MUSTPASS_NATIVE:-}" ]]; then
  # The mustpass file of that release, names as they were then; the pipeline group has
  # since gained a ".monolithic." level.  Keep what matches the pattern and still exists.
  rx="^$(printf '%s' "$1" | sed -e 's/[.]/\\./g' -e 's/[*]/.*/g')\$"
  git -C "$VK_GL_CTS" show "vulkan-cts-$MUSTPASS:external/vulkancts/mustpass/${MUSTPASS%.*}/vk-default.txt" \
    | sed 's/^dEQP-VK\.pipeline\./dEQP-VK.pipeline.monolithic./' | grep -E "$rx" | sort -u > "$OUT/mustpass.txt" || true
  sort -u "$OUT/all.txt" | comm -12 - "$OUT/mustpass.txt" > "$OUT/all_mp.txt"
  echo "mustpass $MUSTPASS: $(wc -l < "$OUT/mustpass.txt") listed, $(wc -l < "$OUT/all_mp.txt") exist in this CTS"
  mv "$OUT/all_mp.txt" "$OUT/all.txt"
fi
total=$(wc -l < "$OUT/all.txt")
echo "$total cases, $JOBS shards"

# Deal the cases out in a fixed pseudo-random order: the list alternates cheap cases
# (NotSupported, e.g. the _compute twins) with real renders, so a fixed stride that
# divides the shard count would give some shards all the work.
shuf --random-source=<(yes) "$OUT/all.txt" > "$OUT/shuffled.txt"
for ((j = 0; j < JOBS; j++)); do
  awk -v n="$JOBS" -v j="$j" '(NR - 1) % n == j' "$OUT/shuffled.txt" > "$OUT/shard_$j.txt"
done
start=$(date +%s)
for ((j = 0; j < JOBS; j++)); do
  [[ -s "$OUT/shard_$j.txt" ]] || continue
  OUT="$OUT/run_$j" NO_FW_BUILD=1 "$HERE/cts_one.sh" --list "$OUT/shard_$j.txt" \
    > "$OUT/shard_$j.log" 2>&1 &
done
wait
elapsed=$(( $(date +%s) - start ))

# Per-case result table, from each shard's deqp log.
: > "$OUT/results.txt"
for ((j = 0; j < JOBS; j++)); do
  log="$OUT/run_$j/deqp.log"
  [[ -f "$log" ]] || continue
  awk '/^Test case \x27/{c=$3; gsub(/\x27|\.\.$/,"",c)}
       /^  (Pass|Fail|NotSupported|QualityWarning|CompatibilityWarning|InternalError|ResourceError|Crash|Timeout)( |$)/ \
         {print $1, c, substr($0, index($0,$2))}' "$log" >> "$OUT/results.txt"
done
{
  echo "pattern: $1   cases: $total   shards: $JOBS   wall: ${elapsed}s"
  awk '{n[$1]++} END{for (k in n) printf "%-22s %d\n", k, n[k]}' "$OUT/results.txt" | sort
} | tee "$OUT/summary.txt"
