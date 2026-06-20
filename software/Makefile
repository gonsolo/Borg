.PHONY: all clean test borg hutt opensbi linux flash-linux

all: borg hutt

borg:
	$(MAKE) -C borg

hutt:
	$(MAKE) -C hutt

test:
	$(MAKE) -C borg/compiler test
	$(MAKE) -C borg test-native

clean:
	$(MAKE) -C borg clean
	$(MAKE) -C hutt clean
	rm -rf $(OPENSBI_OUT) $(LINUX_OUT)

# ---------------------------------------------------------------------------
# OpenSBI — FW_PAYLOAD mode: loads Linux kernel directly.
#
# Requires:
#   OPENSBI_SRC  — path to an opensbi source tree (git clone riscv-software-src/opensbi)
#                  default: ../ext/opensbi
#   LINUX_IMAGE  — path to Image (uncompressed Linux kernel) to embed
#                  default: $(LINUX_OUT)/arch/riscv/boot/Image
#
# The borg platform files are in software/opensbi/platform/borg/ and are
# passed to OpenSBI via PLATFORM_DIR (supported in OpenSBI >= 1.3 as an
# override; older trees need the files copied into $(OPENSBI_SRC)/platform/).
#
# Build output lands in $(OPENSBI_OUT)/platform/borg/firmware/fw_payload.bin
# That binary is then wrapped for FlashBootLoader (4-byte LE size header)
# and flashed at offset 0x400000 in SPI flash.
#
# Toolchain: riscv64-unknown-elf-gcc (bare-metal, provided by Nix flake).
# ---------------------------------------------------------------------------

OPENSBI_SRC  ?= $(CURDIR)/../ext/opensbi
OPENSBI_OUT  ?= $(CURDIR)/../out/software/opensbi
BORG_PLATFORM := $(CURDIR)/opensbi/platform/borg

LINUX_SRC    ?= $(CURDIR)/../ext/linux
LINUX_OUT    ?= $(CURDIR)/../out/software/linux

# DTS lives in software/linux/; dtc is provided by the Nix flake.
BORG_DTS     := $(CURDIR)/linux/borg.dts
BORG_DTB     := $(LINUX_OUT)/borg.dtb

LINUX_IMAGE  ?= $(LINUX_OUT)/arch/riscv/boot/Image

opensbi: $(BORG_DTB) $(LINUX_IMAGE)
	@echo "[opensbi] building FW_PAYLOAD with Borg platform..."
	$(MAKE) -C $(OPENSBI_SRC) \
	    CROSS_COMPILE=riscv64-unknown-elf- \
	    PLATFORM=borg \
	    PLATFORM_DIR=$(BORG_PLATFORM) \
	    FW_TEXT_START=0x00000000 \
	    FW_PAYLOAD_OFFSET=0x00200000 \
	    FW_FDT_PATH=$(BORG_DTB) \
	    FW_PAYLOAD_PATH=$(LINUX_IMAGE) \
	    O=$(OPENSBI_OUT)
	@echo "[opensbi] done → $(OPENSBI_OUT)/platform/borg/firmware/fw_payload.bin"

# Compile the device tree source to a flat binary.
$(BORG_DTB): $(BORG_DTS)
	@mkdir -p $(dir $@)
	dtc -I dts -O dtb -o $@ $<

# ---------------------------------------------------------------------------
# Linux kernel — minimal RV64 build using borg_rv64_defconfig.
#
# CONFIG_INITRAMFS_SOURCE can be overridden to point at a built buildroot
# rootfs cpio archive; for minimal smoke-test pass empty string (no rootfs,
# kernel panics after mounting initrd, but OpenSBI+MMU are exercised).
#
# Toolchain: riscv64-unknown-linux-gnu-gcc (Linux ABI, provided by Nix flake).
# ---------------------------------------------------------------------------

LINUX_DEFCONFIG := $(CURDIR)/linux/configs/borg_rv64_defconfig

linux: $(LINUX_OUT)/.config
	@echo "[linux] building kernel..."
	$(MAKE) -C $(LINUX_SRC) \
	    ARCH=riscv \
	    CROSS_COMPILE=riscv64-unknown-linux-gnu- \
	    O=$(LINUX_OUT) \
	    Image
	@echo "[linux] done → $(LINUX_IMAGE)"

$(LINUX_OUT)/.config: $(LINUX_DEFCONFIG)
	@mkdir -p $(LINUX_OUT)
	$(MAKE) -C $(LINUX_SRC) \
	    ARCH=riscv \
	    CROSS_COMPILE=riscv64-unknown-linux-gnu- \
	    O=$(LINUX_OUT) \
	    KCONFIG_ALLCONFIG=$(LINUX_DEFCONFIG) \
	    allnoconfig

# ---------------------------------------------------------------------------
# Flash: wrap fw_payload.bin for FlashBootLoader and write to SPI flash.
#
# FlashBootLoader protocol: first 4 bytes = LE uint32 byte length of payload,
# followed by the payload itself.  The ULX3S flash target reads from offset
# 0x400000; write the wrapped image there.
#
# The fpga/ulx3s/Makefile's `flash-vkcube` target is the model; this mirrors
# it for the Linux payload.
#
# Toolchain: openFPGALoader (provided by Nix flake).
# Flash offset: 0x400000 (4MB) — same offset used by flash-vkcube.
# ---------------------------------------------------------------------------

FW_PAYLOAD_BIN := $(OPENSBI_OUT)/platform/borg/firmware/fw_payload.bin
FW_WRAPPED     := $(OPENSBI_OUT)/platform/borg/firmware/fw_payload_wrapped.bin

flash-linux: $(FW_WRAPPED)
	@echo "[flash-linux] writing to SPI flash at 0x400000..."
	openFPGALoader -b ulx3s --write-flash --unprotect-flash -o 0x400000 $<
	openFPGALoader -b ulx3s -r
	@echo "[flash-linux] done — attach tio now."

$(FW_WRAPPED): $(FW_PAYLOAD_BIN)
	@python3 -c "import struct; d=open('$<','rb').read(); open('$@','wb').write(struct.pack('<I',len(d))+d); print(f'  {len(d)} bytes + header -> $@')"

$(FW_PAYLOAD_BIN): opensbi
