# Programs for Hutt's Linux (rv64ima, lp64 soft-float, no C, static musl) on borgvk without a
# loader: borgvk_static with an app's objects linked in. Needs the dev shell and the rootfs
# toolchain (make -C ../rootfs rootfs); borgvk needs a Rust std for Hutt's ISA (rust/setup.sh).
# `make` builds $(OUT)/borgvk_clear, the board test of clear_test/.

TOP     := $(shell git rev-parse --show-toplevel)
OUT     := $(TOP)/out/software/hutt
WRAPPER := $(TOP)/out/software/rootfs-build/musl-install/bin/rv64-musl-gcc
GCCINC  := $(shell riscv64-none-elf-gcc -print-file-name=include)
CROSS_BASE := $(OUT)/rv64-musl.cross
CROSS   := $(OUT)/rv64-musl-rust.cross
RUST    := $(OUT)/rust
MESA_B  := $(OUT)/mesa
CT      := $(OUT)/clear_test
MESON_OPTS := -Dvulkan-drivers=borg -Dgallium-drivers= -Dplatforms= -Dllvm=disabled -Dglx=disabled \
  -Dtools= -Dvideo-codecs= -Dborg-compiler=full -Dborg-small-nir=true -Dvulkan-layers= -Dglvnd=disabled \
  -Dgbm=disabled -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dopengl=false -Dbuildtype=minsize \
  -Db_ndebug=true -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled -Dshader-cache=disabled \
  -Dzstd=disabled -Dspirv-tools=disabled -Dutil-without-cpp=true -Dborg-without-libdrm=true \
  -Ddefault_library=static

.PHONY: all clean FORCE
all: $(OUT)/borgvk_clear

$(CROSS_BASE): rv64-musl.cross
	mkdir -p $(OUT)
	sed -e "s|@WRAPPER@|$(WRAPPER)|" -e "s|@GCCINC@|$(GCCINC)|" $< > $@

$(RUST)/rustc-cross: rust/setup.sh rust/unwind_stub.c rust/hello.rs
	OUT=$(RUST) rust/setup.sh

$(CROSS): $(CROSS_BASE) $(RUST)/rustc-cross
	sed -e "s|^\[binaries\]|&\nrust = ['$(RUST)/rustc-cross']|" \
	    -e "s|^c_link_args = .*|c_link_args = ['-static', '$(RUST)/lib/unwind_stub.o']|" $(CROSS_BASE) > $@

$(CT)/tri_%.h: clear_test/tri.%
	mkdir -p $(CT)
	glslangValidator -V --vn tri_$* -o $@ $< > /dev/null

$(CT)/borgvk_clear_test.o: clear_test/borgvk_clear_test.c $(CT)/tri_vert.h $(CT)/tri_frag.h
	$(WRAPPER) -c -O2 -D_GNU_SOURCE -idirafter $(GCCINC) -I$(TOP)/mesa/include -I$(CT) -o $@ $<

$(OUT)/borgvk_clear: $(CT)/borgvk_clear_test.o $(CROSS) FORCE
	if [ ! -f $(MESA_B)/build.ninja ]; then env -u CC -u CXX -u PKG_CONFIG_PATH -u PKG_CONFIG_PATH_FOR_TARGET -u NIX_CFLAGS_COMPILE \
	    meson setup $(MESA_B) $(TOP)/mesa --cross-file $(CROSS) $(MESON_OPTS) '-Dborg-app-objects=["$<"]'; fi
	env -u NIX_CFLAGS_COMPILE ninja -C $(MESA_B) src/borg/vulkan/borgvk_static
	riscv64-none-elf-strip -o $@ $(MESA_B)/src/borg/vulkan/borgvk_static

FORCE:

clean:
	rm -rf $(OUT)
