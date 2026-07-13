# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0
#
# Builds the RV64 soft-float userspace toolchain (musl + a small compiler-rt
# slice) and a static busybox against it, then assembles the initramfs
# rootfs embedded into the kernel via CONFIG_INITRAMFS_SOURCE.
#
# This whole tree used to be built by hand across several sessions with no
# durable record of the exact commands — every fix (relative-symlink
# busybox install, /init console attach, musl's oldmalloc switch) lived only
# in the gitignored out/ directory and would have been silently lost on a
# clean rebuild. This Makefile is that durable record.
#
# Why a custom toolchain instead of riscv64-unknown-linux-gnu-gcc (used for
# the kernel/OpenSBI): riscv64-none-elf-gcc gives precise control over the
# exact ISA (rv64ima_zicsr_zifencei — no compressed instructions, Hutt has
# no C-extension decoder) and ABI (lp64 soft-float, no F/D extension) that
# Hutt actually implements; the glibc-targeting cross-gcc's multilib
# defaults don't cleanly hit that exact combination. See rv64-musl-gcc's own
# header comment (generated below) for the linking subtleties this requires.
#
# Usage:
#   make -C software/rootfs rootfs      # build everything, populate out/software/rootfs
#   make -C software/rootfs clean       # remove build (not download) artifacts

.PHONY: all rootfs musl compiler-rt linux-headers busybox clean distclean

MUSL_VERSION         := 1.2.5
BUSYBOX_VERSION       := 1.36.1
COMPILER_RT_VERSION   := 18.1.8

MUSL_URL         := https://musl.libc.org/releases/musl-$(MUSL_VERSION).tar.gz
BUSYBOX_URL      := https://busybox.net/downloads/busybox-$(BUSYBOX_VERSION).tar.bz2
COMPILER_RT_URL  := https://github.com/llvm/llvm-project/releases/download/llvmorg-$(COMPILER_RT_VERSION)/compiler-rt-$(COMPILER_RT_VERSION).src.tar.xz

BUILD        := $(CURDIR)/../../out/software/rootfs-build
ROOTFS       := $(CURDIR)/../../out/software/rootfs
MUSL_SRC     := $(BUILD)/musl-$(MUSL_VERSION)
MUSL_INSTALL := $(BUILD)/musl-install
CRT_SRC      := $(BUILD)/compiler-rt/compiler-rt-$(COMPILER_RT_VERSION).src
CRT_BUILD    := $(BUILD)/compiler-rt/build
CRT_LIB      := $(CRT_BUILD)/libcompiler_rt_rv64sf.a
BB_SRC       := $(BUILD)/busybox-$(BUSYBOX_VERSION)
LINUX_HDRS   := $(BUILD)/linux-headers/include
WRAPPER      := $(MUSL_INSTALL)/bin/rv64-musl-gcc

# Hutt's exact ISA/ABI (see docs/A3_hutt_cpu.md): no C-extension, no F/D
# extension. Shared by musl, compiler-rt, and (via the wrapper) busybox.
ARCH_FLAGS := -march=rv64ima_zicsr_zifencei -mabi=lp64

LINUX_SRC ?= $(error LINUX_SRC not set — enter the Nix dev shell first)

all: rootfs

# ---------------------------------------------------------------------------
# musl — built with the OLDMALLOC allocator, not the default mallocng.
#
# A 2026-07-08 session root-caused a mallocng SIGSEGV (musl's default
# allocator since 1.2.1) to a genuine mallocng bug: a heap group's `meta`
# back-pointer was never written before being read, proven via
# virtual-address-filtered store tracing in the Hutt sim harness (see
# project memory / git log around that date) — not a Hutt/kernel/hardware
# issue. oldmalloc is the older, simpler, far more battle-tested dlmalloc-
# style allocator musl still ships as a build-time alternative
# (MALLOC_DIR=oldmalloc) and sidesteps the whole bug class. Do not switch
# back to mallocng without re-verifying that bug is actually fixed upstream.
# ---------------------------------------------------------------------------
musl: $(MUSL_INSTALL)/lib/libc.a $(WRAPPER)

$(BUILD)/musl-$(MUSL_VERSION).tar.gz:
	mkdir -p $(BUILD)
	curl -fL -o $@ $(MUSL_URL)

$(MUSL_SRC)/configure: $(BUILD)/musl-$(MUSL_VERSION).tar.gz
	mkdir -p $(BUILD)
	tar -xzf $< -C $(BUILD)
	touch $@

$(MUSL_SRC)/config.mak: $(MUSL_SRC)/configure
	cd $(MUSL_SRC) && ./configure \
	    --target=riscv64 \
	    --prefix=$(MUSL_INSTALL) \
	    --disable-shared \
	    CC=riscv64-none-elf-gcc \
	    CFLAGS="$(ARCH_FLAGS)"

$(MUSL_INSTALL)/lib/libc.a: $(MUSL_SRC)/config.mak
	$(MAKE) -C $(MUSL_SRC) -j$$(nproc) MALLOC_DIR=oldmalloc
	$(MAKE) -C $(MUSL_SRC) install MALLOC_DIR=oldmalloc

# Cross-compiler wrapper — see the header comment written into the script
# itself (below) for why this exists instead of a plain CC= override.
$(WRAPPER): $(MUSL_INSTALL)/lib/libc.a $(CRT_LIB) $(LINUX_HDRS)/linux/version.h
	mkdir -p $(dir $@)
	@echo '#!/bin/sh' > $@
	@echo '# Cross-compiler wrapper for Hutt'"'"'s ISA (rv64ima_zicsr_zifencei, lp64 soft-float,' >> $@
	@echo '# no compressed instructions) against musl. riscv64-none-elf-gcc was built' >> $@
	@echo '# with --disable-multilib (one baked-in double-float ABI variant only), so its' >> $@
	@echo '# startfile/libgcc auto-lookup either fatally rejects this -march/-mabi at' >> $@
	@echo '# link time, or (if bypassed via -nostartfiles) pulls a double-float libgcc.a' >> $@
	@echo '# / libm.a incompatible with our soft-float objects. Fix: supply musl'"'"'s own' >> $@
	@echo '# crt objects + libc.a + libm.a, and a custom libcompiler_rt_rv64sf.a' >> $@
	@echo '# (double/single/quad-float arithmetic, bswap, clz builtins, compiled from' >> $@
	@echo '# LLVM compiler-rt source for this exact ISA) instead of the toolchain'"'"'s' >> $@
	@echo '# libgcc.a/libm.a — but ONLY for a real final executable link. Any "-lXXX"' >> $@
	@echo '# from the caller is stripped (it would resolve against the toolchain'"'"'s' >> $@
	@echo '# incompatible default LIBRARY_PATH, e.g. newlib'"'"'s libm.a) since our own' >> $@
	@echo '# libc.a/libm.a/compiler-rt archive already cover everything needed. Partial/' >> $@
	@echo '# relocatable links (busybox'"'"'s kbuild does `$$(CC) -nostdlib -r -o' >> $@
	@echo '# built-in.o ...` to merge per-directory objects) must pass through' >> $@
	@echo '# untouched, or crt1.o'"'"'s _start ends up duplicated inside an intermediate' >> $@
	@echo '# object.' >> $@
	@echo 'MUSL="$(MUSL_INSTALL)"' >> $@
	@echo 'CRTLIB="$(CRT_LIB)"' >> $@
	@echo 'KHDRS="$(LINUX_HDRS)"' >> $@
	@echo 'ARGS="$(ARCH_FLAGS) -nostdinc -isystem $$MUSL/include -isystem $$KHDRS"' >> $@
	@echo 'for a in "$$@"; do' >> $@
	@echo '  case "$$a" in' >> $@
	@echo '    -c|-E|-S|-M|-MM|-r) exec riscv64-none-elf-gcc $$ARGS "$$@" ;;' >> $@
	@echo '  esac' >> $@
	@echo 'done' >> $@
	@echo 'HAS_O=0' >> $@
	@echo 'for a in "$$@"; do case "$$a" in -o) HAS_O=1 ;; esac; done' >> $@
	@echo 'if [ "$$HAS_O" = 1 ]; then' >> $@
	@echo '  FILTERED=""' >> $@
	@echo '  for a in "$$@"; do' >> $@
	@echo '    case "$$a" in' >> $@
	@echo '      -l*) ;;' >> $@
	@echo '      *) FILTERED="$$FILTERED $$a" ;;' >> $@
	@echo '    esac' >> $@
	@echo '  done' >> $@
	@echo '  exec riscv64-none-elf-gcc $$ARGS -nostartfiles -nodefaultlibs -static \' >> $@
	@echo '    "$$MUSL/lib/crt1.o" "$$MUSL/lib/crti.o" $$FILTERED "$$MUSL/lib/crtn.o" \' >> $@
	@echo '    "$$MUSL/lib/libc.a" "$$MUSL/lib/libm.a" "$$CRTLIB"' >> $@
	@echo 'else' >> $@
	@echo '  exec riscv64-none-elf-gcc $$ARGS "$$@"' >> $@
	@echo 'fi' >> $@
	chmod +x $@

# ---------------------------------------------------------------------------
# compiler-rt — just the ~36 soft-float/quad-float arithmetic builtins musl
# and busybox actually reference (double/single/quad add/sub/mul/div/compare/
# convert, bswap, clz, fp_mode). riscv64-none-elf-gcc's own libgcc.a is
# double-float-ABI and incompatible; this replaces it (see the wrapper
# script above). Built directly from upstream LLVM compiler-rt source, NOT
# musl's own libgcc replacement, because musl doesn't ship the quad-float
# (long double) routines glibc-derived code sometimes pulls in.
# ---------------------------------------------------------------------------
CRT_BUILTINS := adddf3 addtf3 bswapdi2 bswapsi2 clzdi2 clzsi2 comparedf2 \
                comparetf2 divdf3 divtf3 extenddftf2 extendsfdf2 extendsftf2 \
                fixdfdi fixdfsi fixtfsi fixunsdfdi fixunsdfsi fixunstfsi \
                floatdidf floatsidf floatsisf floatsitf floatundidf \
                floatunsidf floatunsisf floatunsitf muldf3 mulsf3 multf3 \
                subdf3 subtf3 truncdfsf2 trunctfdf2 trunctfsf2

compiler-rt: $(CRT_LIB)

$(BUILD)/compiler-rt/compiler-rt.tar.xz:
	mkdir -p $(BUILD)/compiler-rt
	curl -fL -o $@ $(COMPILER_RT_URL)

$(CRT_SRC)/lib/builtins/adddf3.c: $(BUILD)/compiler-rt/compiler-rt.tar.xz
	tar -xJf $< -C $(BUILD)/compiler-rt
	touch $@

$(CRT_LIB): $(CRT_SRC)/lib/builtins/adddf3.c
	mkdir -p $(CRT_BUILD)
	for f in $(CRT_BUILTINS); do \
	    riscv64-none-elf-gcc $(ARCH_FLAGS) -O2 -fno-builtin -c \
	        -I$(CRT_SRC)/lib/builtins \
	        -o $(CRT_BUILD)/$$f.o \
	        $(CRT_SRC)/lib/builtins/$$f.c || exit 1; \
	done
	riscv64-none-elf-gcc $(ARCH_FLAGS) -O2 -fno-builtin -c \
	    -I$(CRT_SRC)/lib/builtins \
	    -o $(CRT_BUILD)/fp_mode.o \
	    $(CRT_SRC)/lib/builtins/riscv/fp_mode.c
	riscv64-none-elf-ar rcs $@ $(CRT_BUILD)/*.o

# ---------------------------------------------------------------------------
# Kernel UAPI headers (for busybox applets that need e.g. linux/kd.h).
# ---------------------------------------------------------------------------
linux-headers: $(LINUX_HDRS)/linux/version.h

$(LINUX_HDRS)/linux/version.h:
	mkdir -p $(BUILD)/linux-headers
	$(MAKE) -C $(LINUX_SRC) ARCH=riscv \
	    INSTALL_HDR_PATH=$(BUILD)/linux-headers \
	    headers_install

# ---------------------------------------------------------------------------
# busybox — static, against musl, via the wrapper above.
#
# busybox.config (committed, this directory) was reduced from `make
# defconfig` (NOT allnoconfig+KCONFIG_ALLCONFIG — confirmed in an earlier
# session that mechanism silently drops symbols for this busybox version)
# by disabling procps/e2fsprogs/mailutils/runit/selinux/modutils/networking/
# tc/etc via sed+oldconfig. 263 applets, static soft-float binary.
#
# NOTE the CC/STRIP overrides below are required on the command line: this
# busybox.config has CONFIG_CROSS_COMPILER_PREFIX="" (empty), so a bare
# `make` silently falls back to the HOST gcc/strip — that produced a
# same-size-looking but totally wrong x86 binary the one time this was
# missed. Always build busybox through this Makefile, not by hand.
# ---------------------------------------------------------------------------
BB_UNSTRIPPED := $(BB_SRC)/busybox_unstripped
BB_STRIPPED   := $(BB_SRC)/busybox

busybox: $(BB_STRIPPED)

$(BUILD)/busybox-$(BUSYBOX_VERSION).tar.bz2:
	mkdir -p $(BUILD)
	curl -fL -o $@ $(BUSYBOX_URL)

$(BB_SRC)/Makefile: $(BUILD)/busybox-$(BUSYBOX_VERSION).tar.bz2
	mkdir -p $(BUILD)
	tar -xjf $< -C $(BUILD)
	touch $@

$(BB_SRC)/.config: busybox.config $(BB_SRC)/Makefile
	cp busybox.config $(BB_SRC)/.config
	$(MAKE) -C $(BB_SRC) CC=$(WRAPPER) oldconfig

$(BB_STRIPPED): $(BB_SRC)/.config $(WRAPPER)
	$(MAKE) -C $(BB_SRC) CC=$(WRAPPER) STRIP=riscv64-none-elf-strip -j$$(nproc)
	$(MAKE) -C $(BB_SRC) CC=$(WRAPPER) STRIP=riscv64-none-elf-strip -j$$(nproc) busybox

# ---------------------------------------------------------------------------
# bench — steady-state syscall-latency sanity check (task #15 follow-up),
# run by /init as T3. Static, same wrapper/ISA as busybox.
# ---------------------------------------------------------------------------
BENCH := $(ROOTFS)/bin/bench

$(BENCH): bench.c $(WRAPPER)
	mkdir -p $(ROOTFS)/bin
	$(WRAPPER) $(ARCH_FLAGS) -static -O2 -o $@ bench.c

# ---------------------------------------------------------------------------
# rootfs — busybox's OWN install mechanism (applets/install.sh --symlinks)
# produces correctly RELATIVE symlinks (bin/foo -> busybox, sbin/foo ->
# ../bin/busybox, etc.) — do NOT hand-roll this with a shell loop again:
# an earlier session did exactly that and every applet symlink ended up
# pointing at the HOST'S ABSOLUTE build path, which silently breaks once
# this tree becomes the actual root filesystem (ENOENT on /bin/sh at boot).
# init (committed, this directory) mounts proc/sys/devtmpfs and explicitly
# attaches to /dev/hvc0 before exec'ing /bin/sh — see its own header comment
# for why (kernel_init() can't open an initial console on this platform, so
# PID 1 inherits unusable stdio without this).
# ---------------------------------------------------------------------------
rootfs: $(BB_STRIPPED) $(BENCH)
	mkdir -p $(ROOTFS)
	$(MAKE) -C $(BB_SRC) CC=$(WRAPPER) CONFIG_PREFIX=$(ROOTFS) install
	mkdir -p $(ROOTFS)/proc $(ROOTFS)/sys $(ROOTFS)/dev $(ROOTFS)/tmp \
	         $(ROOTFS)/root $(ROOTFS)/etc $(ROOTFS)/mnt
	cp init $(ROOTFS)/init
	chmod 755 $(ROOTFS)/init
	@echo "rootfs assembled at $(ROOTFS)"

clean:
	rm -rf $(BUILD)/musl-$(MUSL_VERSION) $(MUSL_INSTALL) \
	       $(BUILD)/compiler-rt/compiler-rt-$(COMPILER_RT_VERSION).src $(CRT_BUILD) \
	       $(BB_SRC) $(BUILD)/linux-headers $(ROOTFS)

# distclean also drops the downloaded tarballs (forces a re-fetch).
distclean: clean
	rm -f $(BUILD)/musl-$(MUSL_VERSION).tar.gz \
	      $(BUILD)/compiler-rt/compiler-rt.tar.xz \
	      $(BUILD)/busybox-$(BUSYBOX_VERSION).tar.bz2
