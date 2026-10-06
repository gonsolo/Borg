// SPDX-License-Identifier: GPL-3.0-or-later
// The Borg device seen from userspace: registers and GPU memory, either through the DRM render
// node (/dev/dri/renderD128) or, for host tests, a `direct_sim --raw` process ($BORG_HW_RAW).
// Provides the bh_* hooks borg_core.c (host build) calls. Register and memory writes are
// batched; any read, and a switch between the two kinds, flushes.
#pragma once
#include <stdint.h>

int borg_hw_open(void);                  // 0 on success; idempotent
void borg_hw_flush(void);                // everything written so far has reached the device
void borg_hw_mem_write(uint32_t off, const void *p, uint32_t len);   // 4-byte aligned
void borg_hw_mem_read(uint32_t off, void *p, uint32_t len);
