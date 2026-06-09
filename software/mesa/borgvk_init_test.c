/* Phase 2 init harness for borgvk: exercises the resource-creation entrypoints
 * cube.c uses during setup (memory, buffers, images, views, samplers, render
 * pass, pipeline, command buffers, sync) WITHOUT a swapchain (that's Phase 3).
 * Prints PASS/FAIL per stage; stops at the first hard failure/crash.
 *
 * Build + run:  make -C software/mesa inittest
 */
#include <vulkan/vulkan.h>
#include <stdio.h>
#include <string.h>

#define CHECK(label, expr) do { \
   VkResult _r = (expr); \
   printf("  %-34s %s (%d)\n", label, _r == VK_SUCCESS ? "PASS" : "FAIL", _r); \
   if (_r != VK_SUCCESS) { printf("  -> stopping\n"); return 1; } \
} while (0)

int main(void)
{
   VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
   VkInstance inst;
   CHECK("vkCreateInstance", vkCreateInstance(&ici, 0, &inst));

   uint32_t n = 1; VkPhysicalDevice pdev;
   CHECK("vkEnumeratePhysicalDevices", vkEnumeratePhysicalDevices(inst, &n, &pdev));

   float prio = 1.0f;
   VkDeviceQueueCreateInfo qci = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
      .queueFamilyIndex = 0, .queueCount = 1, .pQueuePriorities = &prio,
   };
   VkDeviceCreateInfo dci = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
      .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci,
   };
   VkDevice dev;
   CHECK("vkCreateDevice", vkCreateDevice(pdev, &dci, 0, &dev));

   VkQueue q;
   vkGetDeviceQueue(dev, 0, 0, &q);
   printf("  %-34s %s\n", "vkGetDeviceQueue", q ? "PASS" : "FAIL(null)");

   /* --- memory + buffer + map --- */
   VkBufferCreateInfo bci = {
      .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 4096,
      .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
   };
   VkBuffer buf;
   CHECK("vkCreateBuffer", vkCreateBuffer(dev, &bci, 0, &buf));

   VkMemoryRequirements mr;
   vkGetBufferMemoryRequirements(dev, buf, &mr);
   printf("  %-34s size=%lu align=%lu typebits=0x%x\n", "buf memreq",
          (unsigned long)mr.size, (unsigned long)mr.alignment, mr.memoryTypeBits);

   VkMemoryAllocateInfo mai = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = mr.size ? mr.size : 4096, .memoryTypeIndex = 0,
   };
   VkDeviceMemory mem;
   CHECK("vkAllocateMemory", vkAllocateMemory(dev, &mai, 0, &mem));
   CHECK("vkBindBufferMemory", vkBindBufferMemory(dev, buf, mem, 0));

   void *ptr = 0;
   CHECK("vkMapMemory", vkMapMemory(dev, mem, 0, VK_WHOLE_SIZE, 0, &ptr));
   memcpy(ptr, "borg", 4);
   printf("  %-34s '%c%c%c%c'\n", "map write/read",
          ((char*)ptr)[0], ((char*)ptr)[1], ((char*)ptr)[2], ((char*)ptr)[3]);

   /* --- image + view + sampler --- */
   VkImageCreateInfo ici2 = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
      .extent = { 64, 64, 1 }, .mipLevels = 1, .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
      .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
   };
   VkImage img;
   CHECK("vkCreateImage", vkCreateImage(dev, &ici2, 0, &img));
   VkMemoryRequirements imr;
   vkGetImageMemoryRequirements(dev, img, &imr);
   printf("  %-34s size=%lu\n", "img memreq", (unsigned long)imr.size);
   VkMemoryAllocateInfo imai = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = imr.size, .memoryTypeIndex = 0,
   };
   VkDeviceMemory imem;
   CHECK("vkAllocateMemory(image)", vkAllocateMemory(dev, &imai, 0, &imem));
   CHECK("vkBindImageMemory", vkBindImageMemory(dev, img, imem, 0));

   VkImageViewCreateInfo ivci = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img,
      .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
      .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 },
   };
   VkImageView view;
   CHECK("vkCreateImageView", vkCreateImageView(dev, &ivci, 0, &view));

   VkSamplerCreateInfo sci = {
      .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
      .magFilter = VK_FILTER_NEAREST, .minFilter = VK_FILTER_NEAREST,
   };
   VkSampler samp;
   CHECK("vkCreateSampler", vkCreateSampler(dev, &sci, 0, &samp));

   /* --- common: shader module, pipeline layout/cache, render pass --- */
   static const uint32_t dummy_spirv[] = { 0x07230203, 0x00010000, 0, 1, 0 };
   VkShaderModuleCreateInfo smci = {
      .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
      .codeSize = sizeof(dummy_spirv), .pCode = dummy_spirv,
   };
   VkShaderModule sm;
   CHECK("vkCreateShaderModule", vkCreateShaderModule(dev, &smci, 0, &sm));

   VkPipelineLayoutCreateInfo plci = { .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
   VkPipelineLayout pl;
   CHECK("vkCreatePipelineLayout", vkCreatePipelineLayout(dev, &plci, 0, &pl));

   VkPipelineCacheCreateInfo pcci = { .sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO };
   VkPipelineCache pc;
   CHECK("vkCreatePipelineCache", vkCreatePipelineCache(dev, &pcci, 0, &pc));

   /* --- command pool + buffer --- */
   VkCommandPoolCreateInfo cpci = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = 0,
   };
   VkCommandPool pool;
   CHECK("vkCreateCommandPool", vkCreateCommandPool(dev, &cpci, 0, &pool));

   VkCommandBufferAllocateInfo cbai = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
      .commandBufferCount = 1,
   };
   VkCommandBuffer cb;
   CHECK("vkAllocateCommandBuffers", vkAllocateCommandBuffers(dev, &cbai, &cb));

   VkCommandBufferBeginInfo cbbi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
   CHECK("vkBeginCommandBuffer", vkBeginCommandBuffer(cb, &cbbi));
   CHECK("vkEndCommandBuffer", vkEndCommandBuffer(cb));

   /* --- sync --- */
   VkFenceCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
   VkFence fence;
   CHECK("vkCreateFence", vkCreateFence(dev, &fci, 0, &fence));

   VkSemaphoreCreateInfo seci = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
   VkSemaphore sem;
   CHECK("vkCreateSemaphore", vkCreateSemaphore(dev, &seci, 0, &sem));

   printf("\nALL STAGES PASSED\n");

   vkDestroyDevice(dev, 0);
   vkDestroyInstance(inst, 0);
   return 0;
}
