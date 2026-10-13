#!/usr/bin/env bash
# Rerun the driver corpus cases and compare the recorded hashes and the result table with ref/.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
NEW="$(realpath -m "${1:-$ROOT/out/borgvk_corpus}")"
rm -rf "$NEW" "$NEW.run"; mkdir -p "$NEW"
BORGVK_CORPUS_DIR="$NEW" BORGVK_SERVE_PARTS=1 BORGVK_CS_PARTS=1 SIM_BUDGET=10 JOBS=10 CHUNK=8 \
  MESA_ROOT="${MESA_ROOT:-$ROOT/mesa}" DIRECT=1 CASELIST="$HERE/cases.txt" OUT="$NEW.run" \
  "$ROOT/scripts/cts_par.sh" corpus > "$NEW.log" 2>&1 || true
cat "$NEW"/*.log | sort -u | grep -vf "$HERE/ref/unstable.txt" > "$NEW.lines" || true
sed 's/ (.*//' "$NEW.run/results.txt" | sort > "$NEW.status"
for rc in "$NEW.run"/run_*.rc; do
  [[ "$(cat "$rc")" == 124 || "$(cat "$rc")" == 137 ]] && { echo "INVALID: $(basename "$rc") timed out"; exit 2; }
done
st=0
if [[ "${2:-}" == "--update" ]]; then
  mkdir -p "$HERE/ref"; cp "$NEW.lines" "$HERE/ref/lines.txt"; cp "$NEW.status" "$HERE/ref/status.txt"; exit 0
fi
echo "hash lines: $(comm -12 "$HERE/ref/lines.txt" "$NEW.lines" | wc -l) identical, $(comm -23 "$HERE/ref/lines.txt" "$NEW.lines" | wc -l) missing, $(comm -13 "$HERE/ref/lines.txt" "$NEW.lines" | wc -l) new"
diff -q "$HERE/ref/lines.txt" "$NEW.lines" > /dev/null || st=1
echo "status: $(diff "$HERE/ref/status.txt" "$NEW.status" | grep -c '^[<>]') lines differ"
diff -q "$HERE/ref/status.txt" "$NEW.status" > /dev/null || st=1
exit $st
