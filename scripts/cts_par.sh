#!/usr/bin/env bash
# Run a pattern of Vulkan CTS cases on N parallel deqp-vk workers.
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
# PASS_DB=file remembers passing cases: cases listed in it are not run again (and are
# counted as Pass), new passes are appended.  It is cleared only by hand (PASS_DB_RESET=1).
#
# The cases are run in small chunks taken from a queue by JOBS workers, each chunk a
# scripts/cts_one.sh --list run with its own scratch dir ($OUT/run_cNNNNN); chunk wall times
# (slowest first) are in $OUT/chunk_times.txt, the summary and a per-case result table land in $OUT/summary.txt
# and $OUT/results.txt.  Same env overrides as cts_one.sh (DIRECT, VK_GL_CTS,
# MESA_ROOT ...); OUT here is the parent directory.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS}"
OUT="${OUT:-/tmp/borg-cts-par}"
JOBS="${JOBS:-$(nproc)}"
DEQP_DIR="$VK_GL_CTS/build/external/vulkancts/modules/vulkan"
[[ $# -eq 1 ]] || { sed -n 2,6p "$0"; exit 2; }
# CASELIST=file: run exactly the cases in that file (one per line); the pattern is a label.
rm -rf "$OUT"; mkdir -p "$OUT"

if [[ -n "${CASELIST:-}" ]]; then
  sort -u "$CASELIST" > "$OUT/all.txt"
elif [[ -n "${MUSTPASS_NATIVE:-}" ]]; then
  # VK_GL_CTS is a checkout of the very release whose mustpass list is wanted: use the
  # names as listed.
  rx="^$(printf '%s' "$1" | sed -e 's/[.]/\\./g' -e 's/[*]/.*/g')\$"
  ver="$(ls "$VK_GL_CTS"/external/vulkancts/mustpass | { grep -E '^[0-9]' || true; } | sort -V | tail -1)"
  if [[ -n "$ver" ]]; then
    git -C "$VK_GL_CTS" show "HEAD:external/vulkancts/mustpass/$ver/vk-default.txt" \
      | grep -E "$rx" | sort -u > "$OUT/all.txt" || true
  else   # current CTS: one 'main' list, split into one file per group
    cat "$VK_GL_CTS"/external/vulkancts/mustpass/main/vk-default/*.txt \
      | grep -E "$rx" | sort -u > "$OUT/all.txt" || true
  fi
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
# Remembered passes: cases in PASS_DB are skipped until the file is cleared (PASS_DB_RESET=1,
# or delete it).  Nothing invalidates it automatically, so clear it after driver changes you
# want re-verified, and do a full run before any claim.
cached=0
if [[ -n "${PASS_DB:-}" ]]; then
  if [[ -n "${PASS_DB_RESET:-}" || ! -f "$PASS_DB" ]]; then
    echo "# manual: delete this file or set PASS_DB_RESET=1 to forget the passes" > "$PASS_DB"
  fi
  tail -n +2 "$PASS_DB" | sort -u > "$OUT/known_pass.txt"
  sort -u "$OUT/all.txt" > "$OUT/all_sorted.txt"
  comm -12 "$OUT/all_sorted.txt" "$OUT/known_pass.txt" > "$OUT/cached_pass.txt"
  comm -23 "$OUT/all_sorted.txt" "$OUT/known_pass.txt" > "$OUT/all.txt"
  cached=$(wc -l < "$OUT/cached_pass.txt")
  echo "$cached cases passed before with this driver: skipped"
fi
total=$(wc -l < "$OUT/all.txt")

# A queue of small chunks, taken by JOBS workers as they become free: a slow case holds up
# only its own chunk, never a fixed share of the list. The list is shuffled (fixed seed) so
# cheap and expensive cases mix. CHUNK overrides the chunk size.
shuf --random-source=<(yes) "$OUT/all.txt" > "$OUT/shuffled.txt"
CHUNK="${CHUNK:-$(( (total + JOBS * 8 - 1) / (JOBS * 8) ))}"
(( CHUNK < 1 )) && CHUNK=1
(( CHUNK > 50 )) && CHUNK=50
echo "$total cases, $JOBS workers, chunks of $CHUNK"
mkdir -p "$OUT/chunks"
[[ $total -gt 0 ]] && split -l "$CHUNK" -d -a 5 "$OUT/shuffled.txt" "$OUT/chunks/c"
export BORGVK_SIM_PARTS=1      # one simulator process per case: the workers are the parallelism
run_chunk() {   # $1 = chunk file; its run directory is named after it
  local d="$OUT/run_$(basename "$1")"
  local t0=$(date +%s)
  OUT="$d" NO_FW_BUILD=1 "$HERE/cts_one.sh" --list "$1" > "$d.log" 2>&1 || true
  echo "$(( $(date +%s) - t0 )) $(basename "$1") $(wc -l < "$1")" >> "$OUT/chunk_times.txt"
}
export -f run_chunk; export OUT HERE
collect() {     # per-case results of the run directories given
  for d in "$@"; do
    [[ -f "$d/deqp.log" ]] || continue
    awk '/^Test case \x27/{c=$3; gsub(/\x27|\.\.$/,"",c)}
         /^  (Pass|Fail|NotSupported|QualityWarning|CompatibilityWarning|InternalError|ResourceError|Crash|Timeout)( |$)/ \
           {print $1, c, substr($0, index($0,$2))}' "$d/deqp.log"
  done
}
start=$(date +%s)
: > "$OUT/chunk_times.txt"
ls "$OUT"/chunks/c* 2>/dev/null | xargs -r -P "$JOBS" -I{} bash -c 'run_chunk {}'
collect "$OUT"/run_c* > "$OUT/results.txt"
# A crash takes the rest of its chunk with it: run the cases that have no result one by one.
awk '{print $2}' "$OUT/results.txt" | sort -u > "$OUT/have.txt"
sort -u "$OUT/all.txt" | comm -23 - "$OUT/have.txt" > "$OUT/missing.txt"
if [[ -s "$OUT/missing.txt" ]]; then
  echo "$(wc -l < "$OUT/missing.txt") cases without a result (a crash in their chunk): run singly"
  mkdir -p "$OUT/singles"
  split -l 1 -d -a 5 "$OUT/missing.txt" "$OUT/singles/s"
  ls "$OUT"/singles/s* | xargs -r -P "$JOBS" -I{} bash -c 'run_chunk {}'
  collect "$OUT"/run_s* >> "$OUT/results.txt"
  awk '{print $2}' "$OUT/results.txt" | sort -u > "$OUT/have.txt"
  comm -23 "$OUT/missing.txt" "$OUT/have.txt" | awk '{print "Crash", $1, "(no result)"}' >> "$OUT/results.txt"
fi
elapsed=$(( $(date +%s) - start ))
sort -rn "$OUT/chunk_times.txt" -o "$OUT/chunk_times.txt"    # slowest chunks first

if [[ -n "${PASS_DB:-}" ]]; then
  awk '$1=="Pass"{print $2}' "$OUT/results.txt" >> "$PASS_DB"
  awk '{print "Pass", $1, "(cached)"}' "$OUT/cached_pass.txt" >> "$OUT/results.txt"
fi
{
  echo "pattern: $1   cases: $((total + cached))   (run: $total, cached passes: $cached)   workers: $JOBS   wall: ${elapsed}s"
  awk '{n[$1]++} END{for (k in n) printf "%-22s %d\n", k, n[k]}' "$OUT/results.txt" | sort
} | tee "$OUT/summary.txt"
