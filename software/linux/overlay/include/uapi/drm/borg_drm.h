/* SPDX-License-Identifier: GPL-2.0 WITH Linux-syscall-note */
/* Borg GPU render node: register writes and word access to the GPU memory carve-out. */
#ifndef _UAPI_BORG_DRM_H_
#define _UAPI_BORG_DRM_H_

#include "drm.h"

#if defined(__cplusplus)
extern "C" {
#endif

#define DRM_BORG_INFO        0x00
#define DRM_BORG_REG_WRITES  0x01
#define DRM_BORG_REG_READ    0x02
#define DRM_BORG_MEM_WRITE   0x03
#define DRM_BORG_MEM_READ    0x04

/* Sizes in bytes of the register block and of the GPU memory. */
struct drm_borg_info {
	__u32 reg_size;
	__u32 mem_size;
	__u32 abi;
	__u32 pad;
};

/* `writes` points to `count` pairs of __u32 {offset, value}. */
struct drm_borg_reg_writes {
	__u64 writes;
	__u32 count;
	__u32 pad;
};

struct drm_borg_reg_read {
	__u32 offset;
	__u32 value;
};

/* Copy `length` bytes (multiple of 4) between `data` and GPU memory at `offset`. */
struct drm_borg_mem {
	__u64 data;
	__u32 offset;
	__u32 length;
};

/* An enum, not #defines: bindgen turns enum constants into Rust constants. */
enum {
	DRM_IOCTL_BORG_INFO       = DRM_IOR(DRM_COMMAND_BASE + DRM_BORG_INFO, struct drm_borg_info),
	DRM_IOCTL_BORG_REG_WRITES = DRM_IOW(DRM_COMMAND_BASE + DRM_BORG_REG_WRITES, struct drm_borg_reg_writes),
	DRM_IOCTL_BORG_REG_READ   = DRM_IOWR(DRM_COMMAND_BASE + DRM_BORG_REG_READ, struct drm_borg_reg_read),
	DRM_IOCTL_BORG_MEM_WRITE  = DRM_IOW(DRM_COMMAND_BASE + DRM_BORG_MEM_WRITE, struct drm_borg_mem),
	DRM_IOCTL_BORG_MEM_READ   = DRM_IOW(DRM_COMMAND_BASE + DRM_BORG_MEM_READ, struct drm_borg_mem),
};

#if defined(__cplusplus)
}
#endif

#endif /* _UAPI_BORG_DRM_H_ */
