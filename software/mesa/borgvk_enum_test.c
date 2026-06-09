/* Minimal enumerate-only smoke test for the borgvk Vulkan driver.
 *
 * Lists the physical device(s) the driver exposes WITHOUT creating a VkDevice
 * (device creation is Phase 2). Build + run via:  make -C software/mesa enumtest
 */
#include <vulkan/vulkan.h>
#include <stdio.h>

int main(void)
{
   VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
   VkInstance inst;
   if (vkCreateInstance(&ici, 0, &inst) != VK_SUCCESS) {
      printf("vkCreateInstance FAILED (is VK_DRIVER_FILES set to the borg ICD?)\n");
      return 1;
   }

   uint32_t n = 0;
   vkEnumeratePhysicalDevices(inst, &n, 0);
   printf("physical device count = %u\n", n);

   VkPhysicalDevice pd[4];
   if (n > 4) n = 4;
   vkEnumeratePhysicalDevices(inst, &n, pd);

   for (uint32_t i = 0; i < n; i++) {
      VkPhysicalDeviceProperties p;
      vkGetPhysicalDeviceProperties(pd[i], &p);
      printf("  [%u] '%s'  api=%u.%u.%u  vendorID=0x%x deviceID=0x%x type=%d\n",
             i, p.deviceName,
             VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion),
             VK_VERSION_PATCH(p.apiVersion), p.vendorID, p.deviceID, p.deviceType);

      uint32_t qn = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(pd[i], &qn, 0);
      VkPhysicalDeviceMemoryProperties m;
      vkGetPhysicalDeviceMemoryProperties(pd[i], &m);
      printf("      queue families = %u, memory heaps = %u, memory types = %u\n",
             qn, m.memoryHeapCount, m.memoryTypeCount);
   }

   vkDestroyInstance(inst, 0);
   return 0;
}
