# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0
#
# Borg SoC OpenSBI platform configuration.
#
# Hardware facts:
#   CPU:       Hutt RV64I + Zicsr/Zifencei only, 1 hart, hartid=0
#              (no M/A/C extensions — see docs/A3_hutt_cpu.md)
#   SDRAM:     32MB at physical 0x00000000 (flat, mapped at CPU addr 0)
#   CLINT:     0x02000000, mtime increments at 25 MHz
#   Debug UART 0x08000018 (write-only, 115200 baud at 25 MHz)
#   Flash firmware offset 0x400000 → FlashBootLoader → SDRAM[0x0]
#
# Boot flow:
#   FlashBootLoader copies flash[0x400000..] → SDRAM[0x0]
#   CPU reset → PC=0 → OpenSBI M-mode init
#   OpenSBI jumps to Linux at FW_PAYLOAD_OFFSET
#
# Build:
#   make CROSS_COMPILE=riscv64-unknown-elf- PLATFORM=borg O=<out>
#
# The DTB is embedded at link time via FW_FDT_PATH; pass an absolute path:
#   make ... FW_FDT_PATH=/path/to/borg.dtb
#
# NOTE: this file is NOT included by OpenSBI's top-level Makefile (only
# objects.mk files get globbed via `find -iname "objects.mk"`) — it exists
# purely as documentation. platform-cflags-y and PLATFORM_RISCV_ISA are set
# in objects.mk (the file that's actually read); setting them here has no
# effect and previously masked a real bug (OpenSBI's build fell back to its
# own default rv64imafdc_zicsr_zifencei, whose compressed instructions
# desynced Hutt's fixed 4-byte fetch and hung the CPU on a wild pointer).

# FW_PAYLOAD mode: OpenSBI binary + embedded Linux kernel in one image.
# Flash image layout:
#   [0x000000] 4-byte size header (FlashBootLoader protocol)
#   [0x000004] fw_payload.bin  (OpenSBI+DTB+kernel, loaded to SDRAM[0x0])
#
# OpenSBI occupies SDRAM[0x0..FW_PAYLOAD_OFFSET-1]; Linux starts at
# SDRAM[FW_PAYLOAD_OFFSET].  2MB gives plenty of headroom for OpenSBI.

FW_TEXT_START        = 0x00000000
FW_PAYLOAD_OFFSET    = 0x00200000
FW_OPTIONS           = 0x0
