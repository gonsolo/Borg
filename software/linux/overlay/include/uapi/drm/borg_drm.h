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
#define DRM_BORG_GEM_CREATE  0x05
#define DRM_BORG_GEM_MMAP    0x06

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

/* A buffer object in system memory: `size` is rounded up to pages; the handle is closed with DRM_IOCTL_GEM_CLOSE. */
struct drm_borg_gem_create {
	__u64 size;
	__u32 handle;
	__u32 pad;
};

/* The offset to pass to mmap() on the render node to map the object. */
struct drm_borg_gem_mmap {
	__u32 handle;
	__u32 pad;
	__u64 offset;
};

/* An enum, not #defines: bindgen turns enum constants into Rust constants. */
enum {
	DRM_IOCTL_BORG_INFO       = DRM_IOR(DRM_COMMAND_BASE + DRM_BORG_INFO, struct drm_borg_info),
	DRM_IOCTL_BORG_REG_WRITES = DRM_IOW(DRM_COMMAND_BASE + DRM_BORG_REG_WRITES, struct drm_borg_reg_writes),
	DRM_IOCTL_BORG_REG_READ   = DRM_IOWR(DRM_COMMAND_BASE + DRM_BORG_REG_READ, struct drm_borg_reg_read),
	DRM_IOCTL_BORG_MEM_WRITE  = DRM_IOW(DRM_COMMAND_BASE + DRM_BORG_MEM_WRITE, struct drm_borg_mem),
	DRM_IOCTL_BORG_MEM_READ   = DRM_IOW(DRM_COMMAND_BASE + DRM_BORG_MEM_READ, struct drm_borg_mem),
	DRM_IOCTL_BORG_GEM_CREATE = DRM_IOWR(DRM_COMMAND_BASE + DRM_BORG_GEM_CREATE, struct drm_borg_gem_create),
	DRM_IOCTL_BORG_GEM_MMAP   = DRM_IOWR(DRM_COMMAND_BASE + DRM_BORG_GEM_MMAP, struct drm_borg_gem_mmap),
};

#if defined(__cplusplus)
}
#endif

#endif /* _UAPI_BORG_DRM_H_ */
