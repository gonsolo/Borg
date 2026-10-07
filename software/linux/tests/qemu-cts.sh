#!/bin/sh
# Tier 2: boot the Rust-driver kernel on QEMU virt (fake Borg, as qemu-borg.sh) and run deqp-vk with borgvk
# from an initrd. usage: qemu-cts.sh '<deqp case pattern>'   (default dEQP-VK.info.*)
set -e
top=$(git rev-parse --show-toplevel)
t=$top/out/software/qemu-cts
k=${KOUT:-$top/out/software/linux-rust-fpu}
dts=$top/out/software/qemu-borg/borg.dts
[ -f "$dts" ] || { echo "run software/linux/tests/qemu-borg.sh once for the device tree" >&2; exit 1; }
mkdir -p "$t"
dtb=$t/borg2g.dtb
# Borg regs/memory at 0x08000000/0x08100000: nothing is mapped there, so any access faults (only info works).
# BORG_FAKE=ram keeps the reserved-RAM stand-in of qemu-borg.sh.
fix='s|reg = <0x00 0x80000000 0x00 0x20000000>|reg = <0x00 0x80000000 0x00 0x80000000>|'
[ "${BORG_FAKE:-fault}" = ram ] || fix="$fix;/borgregs@\|borgmem@/d;/^\tborg@/,/};/s|0x8f000000|0x08000000|;/^\tborg@/,/};/s|0x8f100000|0x08100000|"
sed "$fix" "$dts" | dtc -I dts -O dtb -o "$dtb" 2>/dev/null
"$top/software/linux/tests/qemu-cts-root.sh" "$t/root" >/dev/null
cat > "$t/root/init" <<INIT
#!/bin/sh
mount -t proc none /proc
mount -t devtmpfs none /dev
exec </dev/hvc0 >/dev/hvc0 2>&1
export LD_LIBRARY_PATH=/lib VK_DRIVER_FILES=/etc/vulkan/icd.d/borg.json
ls /dev/dri
${BORG_PROBE:+/bin/borg_drm_test; poweroff -f}
/bin/deqp-vk --deqp-case='${1:-dEQP-VK.info.*}' --deqp-log-filename=/tmp/r.qpa
poweroff -f
INIT
chmod 755 "$t/root/init"
cp "$top/out/software/qemu-borg/borg_drm_test" "$t/root/bin/"
(cd "$t/root" && { echo "dir /tmp 1777 0 0"; find . -mindepth 1 | sed 's|^\./||' | while read -r p; do
  if [ -L "$p" ]; then echo "slink /$p $(readlink "$p") 777 0 0"
  elif [ -d "$p" ]; then echo "dir /$p 755 0 0"
  else echo "file /$p $t/root/$p 755 0 0"; fi; done; } > "$t/initrd.list")
"$k/usr/gen_init_cpio" "$t/initrd.list" > "$t/initrd.cpio"
exec timeout "${QEMU_TIMEOUT:-900}" qemu-system-riscv64 -machine virt -cpu rv64 -m 2G -smp 1 -no-reboot -nographic \
  -bios default -kernel "$k/arch/riscv/boot/Image" -initrd "$t/initrd.cpio" -dtb "$dtb" \
  -append "earlycon=sbi console=hvc0"
