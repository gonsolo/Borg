#!/usr/bin/env bash
# Build the Vulkan CTS release 1.0.2.6 (deqp-vk) next to the current checkout, for running
# the exact Vulkan 1.0 mustpass list (external/vulkancts/mustpass/1.0.2/vk-default.txt,
# 233,795 cases) with scripts/cts_par.sh (MUSTPASS_NATIVE=1).
#
#   scripts/build_cts_1_0_2_6.sh [DEST]      # default $HOME/src/VK-GL-CTS-1.0.2.6
#
# Needs an existing VK-GL-CTS clone (VK_GL_CTS, default $HOME/src/VK-GL-CTS) holding the
# tag vulkan-cts-1.0.2.6, X11 development files, cmake, ninja and a native gcc.
# The 2016 sources need four small fixes for a current toolchain (Python 3, GCC 15);
# they are in cts-1.0.2.6.patch.  The external sources are pinned to the commits the
# release's own external/fetch_sources.py names (that script is Python 2).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VK_GL_CTS="${VK_GL_CTS:-$HOME/src/VK-GL-CTS}"
DEST="${1:-$HOME/src/VK-GL-CTS-1.0.2.6}"

[[ -d "$DEST" ]] || git -C "$VK_GL_CTS" worktree add "$DEST" vulkan-cts-1.0.2.6
cd "$DEST/external"
gitget() {   # url commit dir
  [[ -d "$3/src/.git" ]] && return 0
  mkdir -p "$3"
  git clone -q "$1" "$3/src"
  git -C "$3/src" checkout -q "$2"
}
gitget https://github.com/KhronosGroup/SPIRV-Tools.git 0b0454c42c6b6f6746434bd5c78c5c70f65d9c51 spirv-tools
gitget https://github.com/KhronosGroup/glslang.git      a5c5fb61180e8703ca85f36d618f98e16dc317e2 glslang
gitget https://github.com/KhronosGroup/SPIRV-Headers.git 2bf02308656f97898c5f7e433712f21737c61e4e spirv-headers
gitget https://github.com/madler/zlib.git               v1.2.11 zlib
gitget https://github.com/glennrp/libpng.git            v1.6.27 libpng
cp libpng/src/scripts/pnglibconf.h.prebuilt libpng/src/pnglibconf.h

cd "$DEST"
patch -p1 -N < "$HERE/cts-1.0.2.6.patch" || true     # already-applied hunks are skipped

mkdir -p build && cd build
CC=gcc CXX=g++ cmake .. -G Ninja -DDEQP_TARGET=x11_egl -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++ \
  "-DCMAKE_CXX_FLAGS=-w" "-DCMAKE_C_FLAGS=-w -std=gnu11"
CC=gcc CXX=g++ ninja deqp-vk
echo "built: $DEST/build/external/vulkancts/modules/vulkan/deqp-vk"
