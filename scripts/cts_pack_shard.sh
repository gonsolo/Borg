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
VK_GL_CTS="$P/cts" MESA_ROOT="$P/mesa" SIM_BIN="$P/simulation/direct/direct_sim" DIRECT=1 DIRECT_PROGRESS=1 \
  JOBS="$jobs" SIM_BUDGET="$jobs" CASE_TIMEOUT="${CASE_TIMEOUT:-120}" CASELIST="$P/shards/$shard.txt" OUT="$O/$shard" \
  "$P/scripts/cts_par.sh" "$shard"
cp "$P/manifest.env" "$O/$shard/"
( cd "$O/$shard" && tar --zstd -cf "$O/$shard.tar.zst" manifest.env results.txt summary.txt chunk_times.txt chunk_cycles.txt \
    $(ls -d run_*/result.qpa 2>/dev/null) )
