#!/usr/bin/env bash
# Recompile the corpus cases (about 25 minutes). Fails when a case that passed in status.txt no longer
# passes; blobs that differ from ref/ are only counted, and fail the run with BLOBS=strict (refactors).
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
  if [[ ! -f "$NEW/$b" ]]; then missing=$((missing+1))
  elif cmp -s "$f" "$NEW/$b"; then same=$((same+1))
  else diff=$((diff+1)); fi
done
echo "blobs: identical $same, different $diff, missing $missing (new: $(comm -13 <(ls "$HERE/ref") <(ls "$NEW") | wc -l))"
sort "$NEW.run/results.txt" > "$NEW.status"
regress=$(comm -23 <(grep '^Pass ' "$HERE/status.txt" | sed 's/^Pass //; s/ (.*//' | sort) <(grep '^Pass ' "$NEW.status" | sed 's/^Pass //; s/ (.*//' | sort))
gained=$(comm -13 <(grep '^Pass ' "$HERE/status.txt" | sed 's/^Pass //; s/ (.*//' | sort) <(grep '^Pass ' "$NEW.status" | sed 's/^Pass //; s/ (.*//' | sort))
echo "status: $(grep -c '^Pass ' "$NEW.status") Pass, $(grep -c '^Fail ' "$NEW.status") Fail; lost $(echo -n "$regress" | grep -c .), gained $(echo -n "$gained" | grep -c .)"
[[ -n "$regress" ]] && echo "LOST: $regress"
[[ -z "$regress" ]] && { [[ "${BLOBS:-}" != strict ]] || [[ $diff -eq 0 && $missing -eq 0 ]]; }
