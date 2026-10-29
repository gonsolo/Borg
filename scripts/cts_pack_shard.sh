#!/usr/bin/env bash
# Run one shard of a CTS pack (scripts/cts_pack.sh) on this machine, after its nix-closure.tar has
# been unpacked to /: pack/scripts/cts_pack_shard.sh <shard> <outdir>. Leaves
# <outdir>/<shard>.tar.zst with the results, the summary, the chunk times and cycles, and the qpa logs.
set -euo pipefail
P="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
shard=$1
O="$(realpath -m "$2")"
mkdir -p "$O" "$P/bin"
source "$P/manifest.env"
# cts_one.sh asks pkg-config for the Vulkan loader; the pack's loader is in the Nix closure.
printf '#!/bin/sh\necho %s\n' "$LOADER_DIR" > "$P/bin/pkg-config"
chmod +x "$P/bin/pkg-config"
export PATH="$P/bin:$PATH"
[[ "$KIND" == fractions ]] && export DEQP_ARGS="--deqp-log-images=disable --deqp-log-shader-sources=disable"
jobs=${JOBS:-$(nproc)}
# Runners differ up to 2x in speed: scale the time limits by the golden capture's time here
# against the workstation's 12.2 s, never below 1.
t0=$(date +%s%N)
"$P/simulation/direct/direct_sim" "$P/simulation/direct/bench_capture.bin" 128 128 /dev/null
slow=$(awk -v ns=$(( $(date +%s%N) - t0 )) 'BEGIN { f = ns / 12.2e9; printf "%.2f", f < 1 ? 1 : f }')
scale() { awk -v t="$1" -v f="$slow" 'BEGIN { printf "%d", t * f }'; }
VK_GL_CTS="$P/cts" MESA_ROOT="$P/mesa" SIM_BIN="$P/simulation/direct/direct_sim" DIRECT=1 DIRECT_PROGRESS=1 \
  JOBS="$jobs" SIM_BUDGET="$jobs" TMO="$(scale 900)" CASE_TIMEOUT="$(scale "${CASE_TIMEOUT:-120}")" \
  CASELIST="$P/shards/$shard.txt" OUT="$O/$shard" "$P/scripts/cts_par.sh" "$shard"
cp "$P/manifest.env" "$O/$shard/"
echo "SLOWDOWN=$slow" > "$O/$shard/speed.env"
( cd "$O/$shard" && tar --zstd -cf "$O/$shard.tar.zst" manifest.env speed.env results.txt summary.txt chunk_times.txt chunk_cycles.txt \
    $(ls -d run_*/result.qpa 2>/dev/null) )
