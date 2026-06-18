# Build the borgvk Mesa Vulkan driver — the host-side native ICD for the Borg
# GPU. It runs unmodified Vulkan apps (e.g. Vulkan-Tools/cube.c) on the host and
# ships rendering work to the ULX3S over serial. Driver source lives in the
# `mesa/` submodule under src/borg/vulkan/. See
# ~/.claude/plans/atomic-questing-stream.md.

REPO_ROOT := $(abspath $(CURDIR)/../..)
MESA_SRC  := $(REPO_ROOT)/mesa
BUILD_DIR := $(MESA_SRC)/build-borg
PREFIX    := $(REPO_ROOT)/out/mesa

# Mesa's HOST build must use the native compiler. The Nix dev shell exports
# CC=riscv32-none-elf-gcc for the firmware cross-build, which would otherwise
# make meson cross-compile the driver (sanity check produces a RISC-V binary
# that can't run). Force the native toolchain here.
export CC  := gcc
export CXX := g++

MESON_OPTS := -Dvulkan-drivers=borg -Dgallium-drivers= -Dplatforms=x11 \
              -Dllvm=disabled -Dglx=disabled -Dtools=drm-shim \
              --prefix=$(PREFIX)

# Devenv ICD manifest (points at the in-tree .so; for use via VK_DRIVER_FILES).
ICD := $(BUILD_DIR)/src/borg/vulkan/borg_devenv_icd.$(shell uname -m).json

.PHONY: all configure build install clean icd-path enumtest inittest submittest vulkaninfo

all: build

configure: $(BUILD_DIR)/build.ninja
$(BUILD_DIR)/build.ninja:
	meson setup $(BUILD_DIR) $(MESA_SRC) $(MESON_OPTS)

build: configure
	ninja -C $(BUILD_DIR)
	@echo "borgvk ICD: $(ICD)"

install: build
	ninja -C $(BUILD_DIR) install

# Print the ICD path, e.g.:  VK_DRIVER_FILES=$(make -s -C software/mesa icd-path) vulkaninfo
icd-path:
	@echo $(ICD)

# Enumerate-only smoke test: lists the Borg physical device via the loader.
# (Does NOT create a VkDevice, so it works before Phase 2's CreateDevice.)
enumtest: build
	@gcc borgvk_enum_test.c -o $(BUILD_DIR)/borgvk_enum_test \
		$$(pkg-config --cflags --libs vulkan)
	@echo "--- borgvk physical-device enumeration ---"
	@VK_LOADER_LAYERS_DISABLE='*' VK_DRIVER_FILES="$(ICD)" \
		$(BUILD_DIR)/borgvk_enum_test

# Phase 2 init harness: exercises resource creation (memory/buffer/image/...).
inittest: build
	@gcc borgvk_init_test.c -o $(BUILD_DIR)/borgvk_init_test \
		$$(pkg-config --cflags --libs vulkan)
	@echo "--- borgvk init harness ---"
	@VK_LOADER_LAYERS_DISABLE='*' VK_DRIVER_FILES="$(ICD)" \
		$(BUILD_DIR)/borgvk_init_test

# Phase 3 submit harness: records a frame and submits it, then checks the driver
# pulled the MVP from the bound uniform buffer and emitted the 0xAD serial packet.
# Pure host-side (BORGVK_SERIAL → temp file); no FPGA needed.
submittest: build
	@gcc borgvk_submit_test.c -o $(BUILD_DIR)/borgvk_submit_test \
		$$(pkg-config --cflags --libs vulkan)
	@echo "--- borgvk submit harness ---"
	@VK_LOADER_LAYERS_DISABLE='*' VK_DRIVER_FILES="$(ICD)" \
		$(BUILD_DIR)/borgvk_submit_test

# Run the system vulkaninfo against the borg driver (lists "Borg (ULX3S)").
vulkaninfo: build
	VK_LOADER_LAYERS_DISABLE='*' VK_DRIVER_FILES="$(ICD)" vulkaninfo --summary

clean:
	rm -rf $(BUILD_DIR)
