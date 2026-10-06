# The unmodified Vulkan-Tools cube.c (plus headless.patch) as a static riscv64 program on borgvk,
# for the board: no loader, no window system. Needs the dev shell and the rootfs toolchain
# (make -C ../rootfs rootfs). Result: $(OUT)/vkcube-borg.
TOP     := $(shell git rev-parse --show-toplevel)
OUT     := $(TOP)/out/software/vkcube
WRAPPER := $(TOP)/out/software/rootfs-build/musl-install/bin/rv64-musl-gcc
LHDRS   := $(TOP)/out/software/rootfs-build/linux-headers/include
GCCINC  := $(shell riscv64-none-elf-gcc -print-file-name=include)
VKHDRS  := -I$(TOP)/mesa/include
CROSS   := $(OUT)/rv64-musl.cross
MESA_B  := $(OUT)/mesa
MESON_OPTS := -Dvulkan-drivers=borg -Dgallium-drivers= -Dplatforms= -Dllvm=disabled -Dglx=disabled \
  -Dtools= -Dvideo-codecs= -Dborg-compiler=cache -Dvulkan-layers= -Dglvnd=disabled -Dgbm=disabled \
  -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dopengl=false -Dbuildtype=minsize -Db_ndebug=true \
  -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled -Dshader-cache=disabled \
  -Dutil-without-cpp=true -Dborg-without-libdrm=true -Ddefault_library=static

.PHONY: all clean
all: $(OUT)/vkcube-borg

$(OUT)/cube/cube.c: $(TOP)/Vulkan-Tools/cube/cube.c headless.patch
	rm -rf $(OUT)/cube; mkdir -p $(OUT)/cube
	cp -r $(TOP)/Vulkan-Tools/cube/. $(OUT)/cube/
	cd $(OUT) && patch -s -p1 < $(CURDIR)/headless.patch

$(OUT)/cube.o: $(OUT)/cube/cube.c
	$(WRAPPER) -c -O2 -D_GNU_SOURCE -D__linux__ -DVK_USE_PLATFORM_DISPLAY_KHR -DBORG_HEADLESS \
	    -idirafter $(GCCINC) -I$(LHDRS) -I$(OUT)/cube $(VKHDRS) -o $@ $<

$(CROSS): rv64-musl.cross
	mkdir -p $(OUT)
	sed -e "s|@WRAPPER@|$(WRAPPER)|" -e "s|@GCCINC@|$(GCCINC)|" $< > $@

$(OUT)/vkcube-borg: $(OUT)/cube.o $(CROSS) FORCE
	if [ ! -f $(MESA_B)/build.ninja ]; then env -u CC -u CXX -u PKG_CONFIG_PATH -u PKG_CONFIG_PATH_FOR_TARGET -u NIX_CFLAGS_COMPILE \
	    meson setup $(MESA_B) $(TOP)/mesa --cross-file $(CROSS) $(MESON_OPTS) \
	    '-Dborg-app-objects=["$(OUT)/cube.o"]'; fi
	env -u NIX_CFLAGS_COMPILE ninja -C $(MESA_B) src/borg/vulkan/borgvk_static
	cp $(MESA_B)/src/borg/vulkan/borgvk_static $@

FORCE:

clean:
	rm -rf $(OUT)
