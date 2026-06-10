/* Phase 3 submit-path harness for borgvk: validates the whole render path that
 * runs WITHOUT the FPGA — record a frame (BindDescriptorSets + Draw), submit it,
 * and confirm the driver pulled the MVP from the bound uniform buffer and emitted
 * the framed 0xAD serial packet. We point $BORGVK_SERIAL at a temp file and check
 * its bytes, so this is a pure host-side test (no /dev/ttyUSB0 needed).
 *
 * Build + run:  make -C software/mesa submittest
 */
#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define CHECK(label, expr) do { \
   VkResult _r = (expr); \
   if (_r != VK_SUCCESS) { printf("  %-30s FAIL (%d)\n", label, _r); return 1; } \
} while (0)

int main(void)
{
   /* Route the driver's serial output to a temp file we can read back. */
   char serial_path[] = "/tmp/borgvk_submit_XXXXXX";
   int tmpfd = mkstemp(serial_path);
   if (tmpfd < 0) { perror("mkstemp"); return 1; }
   close(tmpfd);
   setenv("BORGVK_SERIAL", serial_path, 1);

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

   /* Uniform buffer + memory, with a known MVP written into its first 64 bytes
    * (cube.c keeps mvp[4][4] at offset 0 of vktexcube_vs_uniform). */
   VkBufferCreateInfo bci = {
      .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 256,
      .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
   };
   VkBuffer buf;
   CHECK("vkCreateBuffer", vkCreateBuffer(dev, &bci, 0, &buf));
   VkMemoryRequirements mr;
   vkGetBufferMemoryRequirements(dev, buf, &mr);
   VkMemoryAllocateInfo mai = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = mr.size, .memoryTypeIndex = 0,
   };
   VkDeviceMemory mem;
   CHECK("vkAllocateMemory", vkAllocateMemory(dev, &mai, 0, &mem));
   CHECK("vkBindBufferMemory", vkBindBufferMemory(dev, buf, mem, 0));

   float mvp[16];
   for (int i = 0; i < 16; i++)
      mvp[i] = (float)i * 0.5f - 3.0f;   /* arbitrary, includes out-of-[-1,1] */
   void *ptr = 0;
   CHECK("vkMapMemory", vkMapMemory(dev, mem, 0, VK_WHOLE_SIZE, 0, &ptr));
   memcpy(ptr, mvp, sizeof(mvp));

   /* Descriptor set layout/pool/set, binding 0 → the uniform buffer. */
   VkDescriptorSetLayoutBinding bind0 = {
      .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
      .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_VERTEX_BIT,
   };
   VkDescriptorSetLayoutCreateInfo dslci = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
      .bindingCount = 1, .pBindings = &bind0,
   };
   VkDescriptorSetLayout dsl;
   CHECK("vkCreateDescriptorSetLayout", vkCreateDescriptorSetLayout(dev, &dslci, 0, &dsl));

   VkDescriptorPoolSize psize = { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1 };
   VkDescriptorPoolCreateInfo dpci = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
      .maxSets = 1, .poolSizeCount = 1, .pPoolSizes = &psize,
   };
   VkDescriptorPool dpool;
   CHECK("vkCreateDescriptorPool", vkCreateDescriptorPool(dev, &dpci, 0, &dpool));

   VkDescriptorSetAllocateInfo dsai = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
      .descriptorPool = dpool, .descriptorSetCount = 1, .pSetLayouts = &dsl,
   };
   VkDescriptorSet dset;
   CHECK("vkAllocateDescriptorSets", vkAllocateDescriptorSets(dev, &dsai, &dset));

   VkDescriptorBufferInfo dbi = { .buffer = buf, .offset = 0, .range = VK_WHOLE_SIZE };
   VkWriteDescriptorSet write = {
      .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = dset,
      .dstBinding = 0, .descriptorCount = 1,
      .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &dbi,
   };
   vkUpdateDescriptorSets(dev, 1, &write, 0, 0);

   VkPipelineLayoutCreateInfo plci = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
      .setLayoutCount = 1, .pSetLayouts = &dsl,
   };
   VkPipelineLayout pl;
   CHECK("vkCreatePipelineLayout", vkCreatePipelineLayout(dev, &plci, 0, &pl));

   /* Record a frame: bind the set, draw. The runtime enqueues these; submit
    * reads the MVP back out of the bound set. */
   VkCommandPoolCreateInfo cpci = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = 0,
   };
   VkCommandPool cpool;
   CHECK("vkCreateCommandPool", vkCreateCommandPool(dev, &cpci, 0, &cpool));
   VkCommandBufferAllocateInfo cbai = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = cpool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
      .commandBufferCount = 1,
   };
   VkCommandBuffer cb;
   CHECK("vkAllocateCommandBuffers", vkAllocateCommandBuffers(dev, &cbai, &cb));

   VkCommandBufferBeginInfo cbbi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
   CHECK("vkBeginCommandBuffer", vkBeginCommandBuffer(cb, &cbbi));
   vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pl, 0, 1, &dset, 0, 0);
   vkCmdDraw(cb, 36, 1, 0, 0);
   CHECK("vkEndCommandBuffer", vkEndCommandBuffer(cb));

   VkSubmitInfo si = {
      .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
      .commandBufferCount = 1, .pCommandBuffers = &cb,
   };
   CHECK("vkQueueSubmit", vkQueueSubmit(q, 1, &si, VK_NULL_HANDLE));
   CHECK("vkQueueWaitIdle", vkQueueWaitIdle(q));

   /* Validate the emitted serial packet: [0xAD][64B mvp][xor]. */
   FILE *f = fopen(serial_path, "rb");
   if (!f) { perror("fopen"); return 1; }
   uint8_t pkt[66];
   size_t got = fread(pkt, 1, sizeof(pkt), f);
   fclose(f);
   unlink(serial_path);

   if (got != sizeof(pkt)) {
      printf("  packet length                  FAIL (got %zu, want 66)\n", got);
      return 1;
   }
   int ok = (pkt[0] == 0xAD);
   if (memcmp(&pkt[1], mvp, sizeof(mvp)) != 0) ok = 0;
   uint8_t csum = 0;
   for (int i = 1; i <= 64; i++) csum ^= pkt[i];
   if (pkt[65] != csum) ok = 0;

   printf("  marker=0x%02x  mvp_match=%s  csum=%s\n", pkt[0],
          memcmp(&pkt[1], mvp, sizeof(mvp)) == 0 ? "yes" : "NO",
          pkt[65] == csum ? "ok" : "BAD");

   vkDestroyDevice(dev, 0);
   vkDestroyInstance(inst, 0);

   printf("\n%s\n", ok ? "SUBMIT PATH PASSED" : "SUBMIT PATH FAILED");
   return ok ? 0 : 1;
}
