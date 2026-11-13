/* SPDX-License-Identifier: MIT */
/* A program that opens libvulkan itself (as cube.c does) on a static borgvk, which has no loader:
 * dlopen of libvulkan succeeds, and dlsym finds vkGetInstanceProcAddr in borgvk. */
#include <string.h>
#include <vulkan/vulkan.h>

PFN_vkVoidFunction vk_icdGetInstanceProcAddr(VkInstance, const char *);

static char libvulkan;

void *dlopen(const char *file, int mode)
{
   (void)mode;
   return file && strncmp(file, "libvulkan.so", 12) == 0 ? &libvulkan : NULL;
}

void *dlsym(void *handle, const char *name)
{
   return handle == &libvulkan && strcmp(name, "vkGetInstanceProcAddr") == 0 ? (void *)vk_icdGetInstanceProcAddr : NULL;
}

int dlclose(void *handle)
{
   (void)handle;
   return 0;
}

char *dlerror(void)
{
   return "only libvulkan, from borgvk";
}
