#!/bin/sh
# Rust for the rv64 userspace of tier 2: std built offline from the devshell's RUST_LIB_SRC, a rustc wrapper
# and the meson cross file for it. usage: rv64-rust.sh [dir]   (default out/software/rv64-rust)
set -e
top=$(git rev-parse --show-toplevel)
r=$(realpath -m "${1:-$top/out/software/rv64-rust}")
t=riscv64gc-unknown-linux-gnu
: "${RUST_LIB_SRC:?run inside the nix devshell}"
mkdir -p "$r/std-build/src"
cd "$r/std-build"
printf '[package]\nname = "dummy"\nversion = "0.0.0"\nedition = "2021"\n[lib]\npath = "src/lib.rs"\n' > Cargo.toml
: > src/lib.rs
cc=$(command -v riscv64-unknown-linux-gnu-gcc)
RUSTC_BOOTSTRAP=1 __CARGO_TESTS_ONLY_SRC_ROOT="$RUST_LIB_SRC" CARGO_TARGET_RISCV64GC_UNKNOWN_LINUX_GNU_LINKER="$cc" \
  cargo build --release --offline -Zbuild-std=std,panic_abort --target $t \
  --config 'source.crates-io.replace-with="v"' --config "source.v.directory=\"$RUST_LIB_SRC/vendor\""
lib=$r/rsysroot/lib/rustlib/$t/lib
rm -rf "$r/rsysroot"; mkdir -p "$lib"
cp target/$t/release/deps/*.rlib target/$t/release/deps/*.rmeta "$lib/" 2>/dev/null || true
cat > "$r/rustc-rv64" <<W
#!/bin/sh
exec rustc --target $t -L $lib "\$@"
W
chmod +x "$r/rustc-rv64"
printf "[binaries]\nrust = ['%s']\n" "$r/rustc-rv64" > "$r/cross-rust.txt"
echo "$r/cross-rust.txt"
