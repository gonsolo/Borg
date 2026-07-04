# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

# NOTE: config.mk (also in this directory) is NEVER included by OpenSBI's
# top-level Makefile — only objects.mk files get globbed in (see the
# `platform-object-mks` find -iname "objects.mk" rule). PLATFORM_RISCV_ISA
# and platform-cflags-y MUST live here, not in config.mk, or they're silently
# ignored and OpenSBI's Makefile falls back to its own default
# rv64imafdc_zicsr_zifencei — which includes F/D/C extensions Hutt does not
# implement (see docs/A3_hutt_cpu.md). A compressed (C) instruction in
# particular desyncs Hutt's fixed 4-byte fetch/PC-increment, causing it to
# silently misexecute everything downstream. 'm' and 'a' ARE included: Hutt
# implements the full M extension (MUL/DIV/REM + *W forms, see HuttAlu.scala)
# and the full A extension (LR/SC + all AMO* ops, see Hutt.scala's
# sAmoLoadReq/sAmoStoreReq states) — both required just to compile OpenSBI's
# own sbi library (lib/sbi/sbi_init.c uses integer divide directly;
# riscv_atomic.c requires the ISA to declare real atomics).
platform-cflags-y  = -march=rv64ima_zicsr_zifencei -mabi=lp64
PLATFORM_RISCV_ISA = rv64ima_zicsr_zifencei

platform-objs-y += platform.o

# FW_PAYLOAD mode: OpenSBI + embedded Linux kernel in one image (see
# software/Makefile's `opensbi` target for FW_TEXT_START/FW_PAYLOAD_OFFSET/
# FW_FDT_PATH/FW_PAYLOAD_PATH, passed on the command line per build). Without
# this, OpenSBI's `targets-y` list has no firmware-bins-y entries and `make`
# reports success while silently building only libsbi.a/libplatsbi.a — no
# fw_payload.bin is ever produced.
FW_DYNAMIC=n
FW_JUMP=n
FW_PAYLOAD=y
