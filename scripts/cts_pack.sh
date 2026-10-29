#!/usr/bin/env bash
# Pack this build's CTS runtime for GitHub Actions (.github/workflows/cts-pack.yaml): deqp-vk and
# its data, borgvk, the DRM shim, direct_sim, the Nix store paths they load, the CTS scripts, and
# the cases split into shards. Nothing is built here; build first.
#
#   scripts/cts_pack.sh <label> <caselist> <shards>     a case list, e.g. a whole item
#   scripts/cts_pack.sh <label> fractions <shards>      the 16 mustpass fractions, <shards> each
#
# CASE_TIMEOUT (seconds, default 120) is the limit for a case run alone; the pack keeps it.
# The cases in scripts/cts_slow.txt (over 10 min each) are left out unless SLOW=1.
#
# Writes out/cts-pack/<label>/cts-pack.tar.zst and shards.json, the two release assets the
# workflow takes.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS-1.4.6.2}"
MESA_ROOT="${MESA_ROOT:-$REPO/mesa}"
[[ $# -eq 3 ]] || { sed -n 2,13p "$0"; exit 2; }
label=$1 list=$2 n=$3
DEQP_DIR="$VK_GL_CTS/build/external/vulkancts/modules/vulkan"
VK="$MESA_ROOT/build-borg/src/borg"
OUT="$REPO/out/cts-pack/$label"
P="$OUT/pack"
rm -rf "$OUT"; mkdir -p "$P/shards"

# The tree cts_one.sh expects, below the pack root.
c="$P/cts/build/external/vulkancts/modules/vulkan"
mkdir -p "$c" "$P/mesa/build-borg/src/borg/vulkan" "$P/mesa/build-borg/src/borg/drm" "$P/simulation/direct" "$P/scripts"
cp "$DEQP_DIR/deqp-vk" "$c/"
cp -r "$DEQP_DIR/vulkan" "$c/"
cp "$VK/vulkan/libvulkan_borg.so" "$P/mesa/build-borg/src/borg/vulkan/"
sed 's|"library_path": ".*"|"library_path": "./libvulkan_borg.so"|' "$VK/vulkan/borg_devenv_icd.x86_64.json" \
  > "$P/mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json"
cp "$VK/drm/libborg_drm_shim.so" "$P/mesa/build-borg/src/borg/drm/"
cp "$REPO/simulation/direct/direct_sim" "$P/simulation/direct/"
cp "$REPO/simulation/golden/borgvk_capture.bin" "$P/simulation/direct/bench_capture.bin"
cp "$REPO/scripts/cts_one.sh" "$REPO/scripts/cts_par.sh" "$REPO/scripts/cts_pack_shard.sh" "$P/scripts/"

# The Nix store paths the binaries link or load (the Vulkan loader is opened at run time).
loader="$(pkg-config --variable=libdir vulkan)"
bins=("$c/deqp-vk" "$P/mesa/build-borg/src/borg/vulkan/libvulkan_borg.so"
      "$P/mesa/build-borg/src/borg/drm/libborg_drm_shim.so" "$P/simulation/direct/direct_sim" "$loader/libvulkan.so.1")
roots=$({ for b in "${bins[@]}"; do ldd "$b" | grep -o '/nix/store/[^/ ]*'; done; echo "${loader%/lib}"; } | sort -u)
tar -cf "$P/nix-closure.tar" $(nix-store -qR $roots)

# Shards: the cases shuffled with a fixed seed, dealt round robin so every shard gets a mix.
deal() {   # $1 = case list, $2 = shard name prefix
  { [[ ${SLOW:-0} == 1 ]] && cat "$1" || grep -vxFf "$REPO/scripts/cts_slow.txt" "$1" || true; } |
    shuf --random-source=<(yes) | split -n r/"$n" -d -a 3 - "$P/shards/$2"
}
kind=list
if [[ "$list" == fractions ]]; then
  kind=fractions
  M="$VK_GL_CTS/external/vulkancts/mustpass/main"
  t=$(mktemp -d)
  for i in $(seq 0 15); do
    ( cd "$t" && rm -f ./*cases.txt && "$DEQP_DIR/deqp-vk" --deqp-caselist-file="$M/vk-default.txt" \
        --deqp-fraction="$i,16" --deqp-fraction-mandatory-caselist-file="$M/vk-fraction-mandatory-tests.txt" \
        --deqp-runmode=txt-caselist --deqp-archive-dir="$DEQP_DIR" >/dev/null 2>&1 ) || true
    sed -n 's/^TEST: //p' "$t"/*cases.txt | grep -E '^dEQP-VK\.' > "$t/f$i.txt"
    deal "$t/f$i.txt" "f$(printf %02d "$i")_"
  done
  rm -rf "$t"
else
  deal "$list" s
fi
for f in "$P/shards"/*; do mv "$f" "$f.txt"; done

{
  echo "KIND=$kind"
  echo "LOADER_DIR=$loader"
  echo "BORG=$(git -C "$REPO" rev-parse HEAD)$(git -C "$REPO" diff --quiet HEAD -- . ':!mesa' || echo -dirty)"
  echo "MESA=$(git -C "$MESA_ROOT" rev-parse HEAD)$(git -C "$MESA_ROOT" diff --quiet HEAD || echo -dirty)"
  echo "CTS=$(basename "$VK_GL_CTS")"
  echo "CASE_TIMEOUT=${CASE_TIMEOUT:-120}"
  echo "PACKED=$(date -u +%FT%TZ)"
} > "$P/manifest.env"
ls "$P/shards" | sed 's/\.txt$//' | python3 -c 'import json,sys; print(json.dumps([l.strip() for l in sys.stdin]))' > "$OUT/shards.json"
tar --zstd -cf "$OUT/cts-pack.tar.zst" -C "$OUT" pack
rm -rf "$P"
echo "$OUT/cts-pack.tar.zst $(du -h "$OUT/cts-pack.tar.zst" | cut -f1), $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$OUT/shards.json") shards"
