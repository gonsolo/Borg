#!/bin/sh
# A std for Hutt's ISA (rv64ima, lp64 soft-float, no C, musl, panic=abort) and a cross rustc for meson.
# Needs the dev shell (RUST_LIB_SRC) and the rootfs toolchain (make -C ../../rootfs rootfs).
# Result in $OUT: rv64ima-hutt-linux-musl.json, sysroot/, lib/ (libunwind stubs), rustc-cross.
set -e
top=$(git rev-parse --show-toplevel)
here=$top/software/vkcube/rust
out=${OUT:-$top/out/software/vkcube/rust}
cc=$top/out/software/rootfs-build/musl-install/bin/rv64-musl-gcc
real=$(rustc --print sysroot)
t=rv64ima-hutt-linux-musl
mkdir -p "$out"; cd "$out"

RUSTC_BOOTSTRAP=1 rustc -Zunstable-options --print target-spec-json --target riscv64gc-unknown-linux-musl > base.json
python3 - <<'PY'
import json
t = json.load(open("base.json"))
t.update({"features": "+m,+a,-f,-d,-c,-zca,-zcd,-zcf,+zicsr,+zifencei", "llvm-abiname": "lp64",
          "dynamic-linking": False, "eh-frame-header": False, "crt-static-default": True})
for k in [k for k in t if "crt-objects" in k or "link-objects" in k]:
    del t[k]
t["metadata"]["description"] = "Hutt: rv64ima, no C, soft-float, musl"
json.dump(t, open("rv64ima-hutt-linux-musl.json", "w"), indent=1)
PY

# -lunwind resolves to a libunwind.a of stubs: panics abort, std only references these two.
mkdir -p lib; $cc -c -Os -o lib/unwind_stub.o "$here/unwind_stub.c"
rm -f lib/libunwind.a; riscv64-none-elf-ar rcs lib/libunwind.a lib/unwind_stub.o
printf '#!/bin/sh\nexec %s "$@"\n' "$cc" > linker-cross; chmod +x linker-cross

# A sysroot whose src component is rust-lib-src (cargo wants rust-src there to build std).
rm -rf sysroot-src; mkdir -p sysroot-src/lib/rustlib/src/rust
for f in "$real"/*; do [ "$(basename "$f")" = lib ] || ln -s "$f" sysroot-src/; done
for f in "$real"/lib/*; do [ "$(basename "$f")" = rustlib ] || ln -s "$f" sysroot-src/lib/; done
for f in "$real"/lib/rustlib/*; do ln -s "$f" sysroot-src/lib/rustlib/; done
ln -s "$RUST_LIB_SRC" sysroot-src/lib/rustlib/src/rust/library
printf '#!/bin/sh\nexec %s/bin/rustc --sysroot %s/sysroot-src "$@"\n' "$real" "$out" > rustc-w; chmod +x rustc-w
cat > vendor.toml <<EOT
[source.crates-io]
replace-with = "vendored-sources"
[source.vendored-sources]
directory = "$RUST_LIB_SRC/vendor"
EOT

mkdir -p hello/src; cp "$here/hello.rs" hello/src/main.rs
printf '[package]\nname="hello"\nversion="0.1.0"\nedition="2021"\n[profile.release]\nopt-level="s"\npanic="abort"\n' > hello/Cargo.toml
(cd hello && RUSTC=$out/rustc-w RUSTC_BOOTSTRAP=1 CARGO_TARGET_RV64IMA_HUTT_LINUX_MUSL_LINKER=$cc RUSTFLAGS="-Lnative=$out/lib -Clink-arg=$out/lib/unwind_stub.o" \
  cargo build --release --offline --config ../vendor.toml -Zbuild-std=std,panic_abort \
  --target $out/$t.json -Zjson-target-spec)

# Install the built std into a sysroot of its own.
rm -rf sysroot; mkdir -p sysroot/lib/rustlib/$t/lib
cp hello/target/$t/release/deps/*.rlib hello/target/$t/release/deps/*.rmeta sysroot/lib/rustlib/$t/lib/ 2>/dev/null || true
for f in "$real"/*; do [ "$(basename "$f")" = lib ] || ln -sf "$f" sysroot/; done
for f in "$real"/lib/*; do [ "$(basename "$f")" = rustlib ] || ln -sf "$f" sysroot/lib/; done
for f in "$real"/lib/rustlib/*; do ln -sf "$f" sysroot/lib/rustlib/; done
printf '#!/bin/sh\nexport RUSTC_BOOTSTRAP=1\nexec %s/bin/rustc -Zunstable-options --sysroot %s/sysroot --target %s/%s.json -Cpanic=abort -Clinker=%s -Lnative=%s/lib -Clink-arg=%s/lib/unwind_stub.o "$@"\n' \
  "$real" "$out" "$out" "$t" "$cc" "$out" "$out" > rustc-cross; chmod +x rustc-cross
echo "rust cross ready: $out/rustc-cross"
