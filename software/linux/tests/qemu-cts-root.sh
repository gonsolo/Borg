#!/bin/sh
# Assemble the rv64 userspace tree for running deqp-vk + borgvk under QEMU (tier 2).
# usage: qemu-cts-root.sh <dest>   (needs the rv64 deqp-vk, libvulkan_borg.so and the dev shell)
set -e
d=${1:?dest}
top=$(git rev-parse --show-toplevel)
cts=${VK_GL_CTS:-$HOME/src/VK-GL-CTS-1.4.6.2}/build-rv64/external/vulkancts/modules/vulkan
icd=$top/mesa/build-borg-rv64/src/borg/vulkan/libvulkan_borg.so
st=riscv64-unknown-linux-gnu-strip
lib() { ls /nix/store/*riscv64*/lib/"$1" 2>/dev/null | grep -E "${2:-.}" | head -1; }
rm -rf "$d"; mkdir -p "$d/bin" "$d/lib" "$d/etc/vulkan/icd.d"
$st -o "$d/bin/deqp-vk" "$cts/deqp-vk"
$st -o "$d/lib/libvulkan_borg.so" "$icd"
cp -rL "$cts/vulkan" "$d/bin/vulkan" 2>/dev/null || true
for f in ld-linux-riscv64-lp64d.so.1:glibc-.*2.42 libc.so.6:glibc-.*2.42 libm.so.6:glibc-.*2.42 \
         libpthread.so.0:glibc-.*2.42 librt.so.1:glibc-.*2.42 libdl.so.2:glibc-.*2.42 \
         libz.so.1:zlib libzstd.so.1:zstd \
         libdrm.so.2:libdrm libexpat.so.1:expat libvulkan.so.1:vulkan-loader; do
  n=${f%%:*}; pat=${f#*:}; p=$(lib "$n" "$pat"); [ -n "$p" ] || { echo "missing $n" >&2; exit 1; }
  cp -L "$p" "$d/lib/$n"
done
for n in libstdc++.so.6 libgcc_s.so.1; do cp -L "$(riscv64-unknown-linux-gnu-g++ -print-file-name=$n)" "$d/lib/$n"; done
ln -sf lib "$d/lib64"
patchelf --set-interpreter /lib/ld-linux-riscv64-lp64d.so.1 "$d/bin/deqp-vk"
for f in "$d"/bin/deqp-vk "$d"/lib/*.so*; do patchelf --remove-rpath "$f" 2>/dev/null || true; done
cat > "$d/etc/vulkan/icd.d/borg.json" <<J
{"file_format_version":"1.0.1","ICD":{"library_path":"/lib/libvulkan_borg.so","api_version":"1.0.0","library_arch":"64"}}
J
du -sh "$d" | cut -f1
