#!/bin/sh
# Boot the Rust kernel on QEMU virt with a fake Borg (reserved RAM for the registers and the GPU
# memory) and run borg_drm_test against /dev/dri/renderD128.
# Needs the dev shell and a built kernel: software/linux/rust-build.sh
set -e
top=$(git rev-parse --show-toplevel)
here=$top/software/linux
t=$top/out/software/qemu-borg
k=$top/out/software/linux-rust
rootfs_build=$top/out/software/rootfs-build
cc=$rootfs_build/musl-install/bin/rv64-musl-gcc
mkdir -p "$t"

# Device tree: QEMU's own plus the fake Borg at 0x8f000000 (registers) and 0x8f100000 (5 MB).
qemu-system-riscv64 -machine virt,dumpdtb="$t/virt.dtb" -m 512M -smp 1 -nographic >/dev/null 2>&1
dtc -I dtb -O dts "$t/virt.dtb" -o "$t/virt.dts" 2>/dev/null
sed '$d' "$t/virt.dts" > "$t/borg.dts"
cat >> "$t/borg.dts" <<'DTS'
	reserved-memory {
		#address-cells = <0x02>;
		#size-cells = <0x02>;
		ranges;
		borgregs@8f000000 { reg = <0x00 0x8f000000 0x00 0x1000>; no-map; };
		borgmem@8f100000 { reg = <0x00 0x8f100000 0x00 0x500000>; no-map; };
	};
	borg@8f000000 {
		compatible = "gonsolo,borg";
		reg = <0x00 0x8f000000 0x00 0x400 0x00 0x8f100000 0x00 0x500000>;
	};
};
DTS
dtc -I dts -O dtb "$t/borg.dts" -o "$t/borg.dtb" 2>/dev/null

# Test program and an extra initrd whose /init runs it.
$cc -O2 -static -D__linux__ -I"$here/overlay/include/uapi" -I"$rootfs_build/linux-headers/include" \
    -I"$rootfs_build/linux-headers/include/drm" -o "$t/borg_drm_test" "$here/tests/borg_drm_test.c"
cat > "$t/init" <<'INIT'
#!/bin/sh
mount -t proc none /proc
mount -t devtmpfs none /dev
exec </dev/hvc0 >/dev/hvc0 2>&1
dmesg | grep -i borg
/bin/borg_drm_test
poweroff -f
INIT
cat > "$t/initrd.list" <<LIST
dir /bin 755 0 0
dir /dev 755 0 0
dir /proc 755 0 0
file /bin/borg_drm_test $t/borg_drm_test 755 0 0
file /init $t/init 755 0 0
LIST
"$k/usr/gen_init_cpio" "$t/initrd.list" > "$t/initrd.cpio"

exec timeout "${QEMU_TIMEOUT:-120}" qemu-system-riscv64 -machine virt -cpu rv64 -m 512M -smp 1 -nographic \
  -bios default -kernel "$k/arch/riscv/boot/Image" -initrd "$t/initrd.cpio" -dtb "$t/borg.dtb" \
  -append "earlycon=sbi console=hvc0"
