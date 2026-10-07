#!/bin/sh
# Build Linux with Rust and the Borg DRM driver (software/linux/overlay) into out/software/linux-rust.
# Needs the dev shell (LINUX_SRC, RUST_LIB_SRC, KERNEL_BINDGEN). The Rust kernel wants the target
# compiler unwrapped (the Nix wrapper is not meant for cross builds) and gcc for the host tools.
# usage: rust-build.sh [defconfig-fragment] [make targets...]   (default fragment: configs/borg_rv64_rust.frag)
set -e
top=$(git rev-parse --show-toplevel)
here=$top/software/linux
src=$top/out/software/linux-rust-src
out=${KOUT:-$top/out/software/linux-rust}
frag=${1:-$here/configs/borg_rv64_rust.frag}
[ $# -gt 0 ] && shift
[ -d "$src/drivers" ] || "$here/prepare-rust-src.sh" "$src"
cp -r "$here"/overlay/. "$src"/
map=$top/out/hardware/borg/rdl/borg_reg_map.rs
[ "$map" -nt "$top/hardware/rdl/borg.rdl" ] || make -C "$top" rdl > /dev/null
cp "$map" "$src"/drivers/gpu/drm/borg/reg_map.rs
mkdir -p "$out"
if [ ! -f "$out/.config" ] || [ "$frag" -nt "$out/.config" ]; then
  sed "s|@ROOTFS@|$top/out/software/rootfs|" "$frag" > "$out/allconfig.frag"
  make -C "$src" O="$out" ARCH=riscv LLVM=1 HOSTCC=gcc HOSTCXX=g++ BINDGEN="$KERNEL_BINDGEN" KRUSTFLAGS=-Ctarget-feature=-zca \
    KCONFIG_ALLCONFIG="$out/allconfig.frag" allnoconfig > "$out/config.log" 2>&1
fi
exec make -C "$src" O="$out" ARCH=riscv LLVM=1 HOSTCC=gcc HOSTCXX=g++ BINDGEN="$KERNEL_BINDGEN" KRUSTFLAGS=-Ctarget-feature=-zca -j"$(nproc)" "${@:-Image}"
