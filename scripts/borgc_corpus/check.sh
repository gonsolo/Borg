#!/usr/bin/env bash
# Recompile the corpus cases and compare every shader byte for byte with ref/.
#   scripts/borgc_corpus/check.sh [NEW_DIR]     (about 5 minutes, 12 workers)
# Needs a built mesa (MESA_ROOT, default ./mesa) and VK_GL_CTS as for cts_par.sh.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
NEW="${1:-$ROOT/out/borgc_corpus}"
rm -rf "$NEW" "$NEW.run"; mkdir -p "$NEW"
BORGC_CORPUS_DIR="$NEW" MESA_ROOT="${MESA_ROOT:-$ROOT/mesa}" JOBS="${JOBS:-12}" DIRECT=1 \
  CASELIST="$HERE/cases.txt" OUT="$NEW.run" "$ROOT/scripts/cts_par.sh" corpus > "$NEW.log" 2>&1 || true
same=0; diff=0; missing=0
for f in "$HERE"/ref/*.out; do
  b="$(basename "$f")"
  if [[ ! -f "$NEW/$b" ]]; then missing=$((missing+1)); echo "missing $b"
  elif cmp -s "$f" "$NEW/$b"; then same=$((same+1))
  else diff=$((diff+1)); echo "DIFF $b"; fi
done
echo "identical $same, different $diff, missing $missing (new in this run: $(comm -13 <(ls "$HERE/ref") <(ls "$NEW") | wc -l))"
[[ $diff -eq 0 && $missing -eq 0 ]]
