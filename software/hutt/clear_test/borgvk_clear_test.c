/* SPDX-License-Identifier: MIT */
/* borgvk on the board without a loader: a render pass that clears a 64x64 image and draws a
 * triangle over its centre, copied back and checked. Linked into borgvk_static
 * (-Dborg-app-objects); tri_vert.h and tri_frag.h are glslangValidator -V --vn of the shaders. */
#include <stdio.h>
#include <vulkan/vulkan.h>
#include "tri_vert.h"
#include "tri_frag.h"

PFN_vkVoidFunction vk_icdGetInstanceProcAddr(VkInstance, const char *);

#define W 64
#define CHECK(x) do { VkResult _r = (x); if (_r != VK_SUCCESS) { printf("FAIL %s: %d\n", #x, _r); return 1; } } while (0)
#define I(name) PFN_##name name = (PFN_##name)vk_icdGetInstanceProcAddr(inst, #name)
#define D(name) PFN_##name name = (PFN_##name)vkGetDeviceProcAddr(dev, #name)

int
main(void)
{
   VkInstance inst = VK_NULL_HANDLE;
   PFN_vkCreateInstance vkCreateInstance = (PFN_vkCreateInstance)vk_icdGetInstanceProcAddr(NULL, "vkCreateInstance");
   VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_0 };
   CHECK(vkCreateInstance(&(VkInstanceCreateInfo){ .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app }, NULL, &inst));
   I(vkEnumeratePhysicalDevices); I(vkGetPhysicalDeviceProperties); I(vkGetPhysicalDeviceMemoryProperties);
   I(vkCreateDevice); I(vkGetDeviceProcAddr);

   uint32_t n = 1;
   VkPhysicalDevice pdev;
   CHECK(vkEnumeratePhysicalDevices(inst, &n, &pdev));
   VkPhysicalDeviceProperties props;
   vkGetPhysicalDeviceProperties(pdev, &props);
   printf("device: %s\n", props.deviceName);

   float prio = 1.0f;
   VkDeviceQueueCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueCount = 1, .pQueuePriorities = &prio };
   VkDevice dev;
   CHECK(vkCreateDevice(pdev, &(VkDeviceCreateInfo){ .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci }, NULL, &dev));
   D(vkGetDeviceQueue); D(vkCreateImage); D(vkGetImageMemoryRequirements); D(vkAllocateMemory); D(vkBindImageMemory);
   D(vkCreateImageView); D(vkCreateRenderPass); D(vkCreateFramebuffer); D(vkCreateBuffer); D(vkGetBufferMemoryRequirements);
   D(vkBindBufferMemory); D(vkCreateCommandPool); D(vkAllocateCommandBuffers); D(vkBeginCommandBuffer); D(vkCmdBeginRenderPass);
   D(vkCmdEndRenderPass); D(vkCmdCopyImageToBuffer); D(vkEndCommandBuffer); D(vkQueueSubmit); D(vkQueueWaitIdle); D(vkMapMemory);
   D(vkCreateShaderModule); D(vkCreatePipelineLayout); D(vkCreateGraphicsPipelines); D(vkCmdBindPipeline); D(vkCmdDraw);

   VkQueue q;
   vkGetDeviceQueue(dev, 0, 0, &q);
   VkPhysicalDeviceMemoryProperties mp;
   vkGetPhysicalDeviceMemoryProperties(pdev, &mp);
   uint32_t host = 0;
   while (host < mp.memoryTypeCount && !(mp.memoryTypes[host].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))
      host++;

   VkImage img;
   CHECK(vkCreateImage(dev, &(VkImageCreateInfo){ .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
      .format = VK_FORMAT_R8G8B8A8_UNORM, .extent = { W, W, 1 }, .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
      .tiling = VK_IMAGE_TILING_OPTIMAL, .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT }, NULL, &img));
   VkMemoryRequirements mr;
   vkGetImageMemoryRequirements(dev, img, &mr);
   VkDeviceMemory imem, bmem;
   CHECK(vkAllocateMemory(dev, &(VkMemoryAllocateInfo){ .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size }, NULL, &imem));
   CHECK(vkBindImageMemory(dev, img, imem, 0));
   VkImageView view;
   CHECK(vkCreateImageView(dev, &(VkImageViewCreateInfo){ .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = img,
      .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
      .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } }, NULL, &view));
   VkAttachmentDescription att = { .format = VK_FORMAT_R8G8B8A8_UNORM, .samples = VK_SAMPLE_COUNT_1_BIT,
      .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR, .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
      .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE, .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
      .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED, .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL };
   VkAttachmentReference ref = { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
   VkSubpassDescription sub = { .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS, .colorAttachmentCount = 1, .pColorAttachments = &ref };
   VkRenderPass rp;
   CHECK(vkCreateRenderPass(dev, &(VkRenderPassCreateInfo){ .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
      .attachmentCount = 1, .pAttachments = &att, .subpassCount = 1, .pSubpasses = &sub }, NULL, &rp));
   VkFramebuffer fb;
   CHECK(vkCreateFramebuffer(dev, &(VkFramebufferCreateInfo){ .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, .renderPass = rp,
      .attachmentCount = 1, .pAttachments = &view, .width = W, .height = W, .layers = 1 }, NULL, &fb));

   VkShaderModule vs, fs;
   CHECK(vkCreateShaderModule(dev, &(VkShaderModuleCreateInfo){ .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
      .codeSize = sizeof(tri_vert), .pCode = tri_vert }, NULL, &vs));
   CHECK(vkCreateShaderModule(dev, &(VkShaderModuleCreateInfo){ .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
      .codeSize = sizeof(tri_frag), .pCode = tri_frag }, NULL, &fs));
   VkPipelineLayout layout;
   CHECK(vkCreatePipelineLayout(dev, &(VkPipelineLayoutCreateInfo){ .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO }, NULL, &layout));
   VkPipelineShaderStageCreateInfo stages[2] = {
      { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vs, .pName = "main" },
      { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = fs, .pName = "main" },
   };
   VkViewport vp = { 0, 0, W, W, 0, 1 };
   VkRect2D sc = { { 0, 0 }, { W, W } };
   VkPipelineColorBlendAttachmentState blend = { .colorWriteMask = 0xf };
   VkPipeline pipe;
   CHECK(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &(VkGraphicsPipelineCreateInfo){
      .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .stageCount = 2, .pStages = stages,
      .pVertexInputState = &(VkPipelineVertexInputStateCreateInfo){ .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO },
      .pInputAssemblyState = &(VkPipelineInputAssemblyStateCreateInfo){ .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
         .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST },
      .pViewportState = &(VkPipelineViewportStateCreateInfo){ .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
         .viewportCount = 1, .pViewports = &vp, .scissorCount = 1, .pScissors = &sc },
      .pRasterizationState = &(VkPipelineRasterizationStateCreateInfo){ .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
         .cullMode = VK_CULL_MODE_NONE, .lineWidth = 1.0f },
      .pMultisampleState = &(VkPipelineMultisampleStateCreateInfo){ .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
         .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT },
      .pColorBlendState = &(VkPipelineColorBlendStateCreateInfo){ .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
         .attachmentCount = 1, .pAttachments = &blend },
      .layout = layout, .renderPass = rp }, NULL, &pipe));

   VkBuffer buf;
   CHECK(vkCreateBuffer(dev, &(VkBufferCreateInfo){ .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = W * W * 4,
      .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT }, NULL, &buf));
   vkGetBufferMemoryRequirements(dev, buf, &mr);
   CHECK(vkAllocateMemory(dev, &(VkMemoryAllocateInfo){ .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = host }, NULL, &bmem));
   CHECK(vkBindBufferMemory(dev, buf, bmem, 0));

   VkCommandPool pool;
   CHECK(vkCreateCommandPool(dev, &(VkCommandPoolCreateInfo){ .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO }, NULL, &pool));
   VkCommandBuffer cb;
   CHECK(vkAllocateCommandBuffers(dev, &(VkCommandBufferAllocateInfo){ .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1 }, &cb));
   CHECK(vkBeginCommandBuffer(cb, &(VkCommandBufferBeginInfo){ .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO }));
   VkClearValue clear = { .color = { .float32 = { 1.0f, 0.5f, 0.0f, 1.0f } } };
   vkCmdBeginRenderPass(cb, &(VkRenderPassBeginInfo){ .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, .renderPass = rp,
      .framebuffer = fb, .renderArea = { { 0, 0 }, { W, W } }, .clearValueCount = 1, .pClearValues = &clear }, VK_SUBPASS_CONTENTS_INLINE);
   vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
   vkCmdDraw(cb, 3, 1, 0, 0);
   vkCmdEndRenderPass(cb);
   vkCmdCopyImageToBuffer(cb, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buf, 1,
      &(VkBufferImageCopy){ .imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, .imageExtent = { W, W, 1 } });
   CHECK(vkEndCommandBuffer(cb));
   CHECK(vkQueueSubmit(q, 1, &(VkSubmitInfo){ .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cb }, VK_NULL_HANDLE));
   CHECK(vkQueueWaitIdle(q));

   uint8_t *px;
   CHECK(vkMapMemory(dev, bmem, 0, W * W * 4, 0, (void **)&px));
   /* The corner keeps the clear colour, the centre is the triangle's green. */
   const uint8_t *c = px, *m = px + 4 * (W / 2 * W + W / 2);
   int bad = !(c[0] == 255 && c[1] >= 127 && c[1] <= 128 && c[2] == 0 && c[3] == 255) +
             !(m[0] == 0 && m[1] == 255 && m[2] == 0 && m[3] == 255);
   printf("corner %02x %02x %02x %02x, centre %02x %02x %02x %02x\n", c[0], c[1], c[2], c[3], m[0], m[1], m[2], m[3]);
   printf("BORGVK_CLEAR_TEST %s\n", bad ? "FAIL" : "PASS");
   return bad != 0;
}
