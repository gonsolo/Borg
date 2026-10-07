#!/bin/bash
# Tier 2 CTS runner: shards a case list over QEMU guests (deqp-vk + borgvk + Rust DRM driver, faulting Borg).
# A case that touches the Borg panics the guest: it is recorded as GpuAccess and the shard continues in a new guest.
# usage: qemu-cts-run.sh <caselist> <outdir> [jobs]    results: <outdir>/results.txt  ("<status> <case>")
set -u
top=$(git rev-parse --show-toplevel)
cases=$(realpath "${1:?caselist}"); out=$(realpath -m "${2:?outdir}"); jobs=${3:-4}
k=${KOUT:-$top/out/software/linux-rust-fpu}
dts=$top/out/software/qemu-borg/borg.dts
mkdir -p "$out"; rm -rf "$out/root" "$out/shard"*
"$top/software/linux/tests/qemu-cts-root.sh" "$out/root" >/dev/null
msz=$((${GUEST_MEM_MB:-2048} * 1048576)); memhex=$(printf '0x%x 0x%x' $((msz >> 32)) $((msz & 0xffffffff)))
sed "s|reg = <0x00 0x80000000 0x00 0x20000000>|reg = <0x00 0x80000000 $memhex>|;"'/borgregs@\|borgmem@/d;/^\tborg@/,/};/s|0x8f000000|0x08000000|;/^\tborg@/,/};/s|0x8f100000|0x08100000|' "$dts" | dtc -I dts -O dtb -o "$out/borg.dtb" 2>/dev/null
cat > "$out/root/init" <<'INIT'
#!/bin/sh
mount -t proc none /proc
mount -t devtmpfs none /dev
exec </dev/hvc0 >/dev/hvc0 2>&1
export LD_LIBRARY_PATH=/lib VK_DRIVER_FILES=/etc/vulkan/icd.d/borg.json
/bin/deqp-vk --deqp-caselist-file=/cases.txt --deqp-log-filename=/tmp/r.qpa
poweroff -f
INIT
chmod 755 "$out/root/init"
list() { echo "dir /tmp 1777 0 0"; (cd "$out/root" && find . -mindepth 1 | sed 's|^\./||') | while read -r p; do
  if [ -L "$out/root/$p" ]; then echo "slink /$p $(readlink "$out/root/$p") 777 0 0"
  elif [ -d "$out/root/$p" ]; then echo "dir /$p 755 0 0"
  else echo "file /$p $out/root/$p 755 0 0"; fi; done; }
list > "$out/base.list"; "$k/usr/gen_init_cpio" "$out/base.list" > "$out/base.cpio"

shard() {   # $1 = shard number
  local d=$out/shard$1 todo=$out/shard$1/todo.txt; : > "$d/results.txt"
  local n=0
  while [ -s "$todo" ]; do
    n=$((n+1))
    cp "$todo" "$d/cases.txt"
    printf 'file /cases.txt %s 644 0 0\n' "$d/cases.txt" > "$d/c.list"
    "$k/usr/gen_init_cpio" "$d/c.list" > "$d/c.cpio"; cat "$out/base.cpio" "$d/c.cpio" > "$d/initrd.cpio"
    timeout "${BOOT_TIMEOUT:-1800}" qemu-system-riscv64 -machine virt -cpu rv64 -m ${GUEST_MEM_MB:-2048}M -smp 1 -no-reboot -nographic \
      -bios default -kernel "$k/arch/riscv/boot/Image" -initrd "$d/initrd.cpio" -dtb "$out/borg.dtb" \
      -append "earlycon=sbi console=hvc0" > "$d/boot.$n.log" 2>&1; cp "$d/boot.$n.log" "$d/boot.log"
    tr -d '\r' < "$d/boot.log" | awk '/^Test case \x27/{c=$3; gsub(/\x27|\.\.$/,"",c); open=c; next}
         /^  (Pass|Fail|NotSupported|QualityWarning|CompatibilityWarning|InternalError|ResourceError|Crash|Timeout)( |$)/ \
           {print $1, c; open=""}
         END{ if (open != "") print "OPEN", open }' > "$d/boot.res"
    local why=Crash; grep -aq "Kernel panic" "$d/boot.log" && why=GpuAccess
    grep -v '^OPEN ' "$d/boot.res" >> "$d/results.txt"
    local open; open=$(sed -n 's/^OPEN //p' "$d/boot.res")
    [ -n "$open" ] && echo "$why $open" >> "$d/results.txt"
    echo "boot $n: todo=$(wc -l < "$todo") open=${open:-none} why=$why" >> "$d/trace.txt"
    awk '{print $2}' "$d/results.txt" | sort -u > "$d/have.txt"
    grep -vxFf "$d/have.txt" "$todo" > "$d/todo.new" || true
    if [ -z "$open" ] && [ "$(wc -l < "$d/todo.new")" = "$(wc -l < "$todo")" ]; then   # no progress: do not loop forever
      head -1 "$todo" | sed 's/^/BootFail /' | awk '{print $1, $2}' >> "$d/results.txt"; sed -i 1d "$todo"; continue
    fi
    mv "$d/todo.new" "$todo"
  done
}
total=$(wc -l < "$cases")
for ((i=0;i<jobs;i++)); do mkdir -p "$out/shard$i"; awk -v n="$jobs" -v i="$i" '(NR-1)%n==i' "$cases" > "$out/shard$i/todo.txt"; done
for ((i=0;i<jobs;i++)); do shard $i & done; wait
cat "$out"/shard*/results.txt > "$out/results.txt"
echo "$total cases"; awk '{print $1}' "$out/results.txt" | sort | uniq -c
