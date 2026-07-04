# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

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
