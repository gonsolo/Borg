import re

with open('docs/A0_roadmap.md', 'r') as f:
    content = f.read()

# 1. Update Phase headers
content = content.replace(
    '## Phase 1: CPU + FP16 Shader Co-Processor (Complete)',
    '## Phase 1: Foundation & First Silicon (Grant Tasks 1-4) ✅'
)
content = content.replace(
    '## Phase 2: GPU Autonomy (Steps 21–29)',
    '## Phase 2: GPU Autonomy & Fidelity (Grant Task 5) 🏃'
)
content = content.replace(
    '## Phase 3: Linux-Capable CPU (Steps 28–34)',
    '## Phase 3: Linux-Capable CPU (Grant Task 6)'
)
content = content.replace(
    '## Phase 4: Mesa Vulkan Driver (Steps 35–39)',
    '## Phase 4: Mesa Vulkan Driver (Grant Task 7)'
)

# 2. Extract Phase 5 and insert it before Phase 3
phase_5_pattern = re.compile(r'## Phase 5: Mobile GPU Fidelity.*?## Phase 6: Vulkan 1\.0 Conformance', re.DOTALL)
phase_5_match = phase_5_pattern.search(content)

if phase_5_match:
    phase_5_text = phase_5_match.group(0)
    # Remove Phase 5 from its original location
    content = content.replace(phase_5_text, '## Phase 6: Vulkan 1.0 Conformance')
    
    # Clean up the Phase 5 text to become just normal steps in Phase 2
    # The header "## Phase 5: Mobile GPU Fidelity (Steps 40–44)" should be removed
    phase_5_text_cleaned = re.sub(r'## Phase 5: Mobile GPU Fidelity[^\n]*\n\nTarget: [^\n]*\n\n', '', phase_5_text)
    # Remove the Phase 6 header that was caught in the match
    phase_5_text_cleaned = phase_5_text_cleaned.replace('## Phase 6: Vulkan 1.0 Conformance', '')
    
    # We also need to add a note about hardware interpolation as Step 35 (or step 31.5)
    # The steps from phase 5 are numbered 40-45. We should probably renumber them to 32-37.
    # Actually, we can just insert them right before Phase 3.
    
    # Let's find Phase 3
    phase_3_idx = content.find('## Phase 3: Linux-Capable CPU (Grant Task 6)')
    if phase_3_idx != -1:
        # Insert cleaned Phase 5 text before Phase 3
        # We add "### Step 31.5: Fragment Interpolation (Hardware-Assisted)\n\nOptimize shader interpolation path to utilize the hardware edge-equation signals for perspective-correct barycentric weights. [Grant Task 5]\n\n"
        interpolation_step = "### Step 32: Fragment Interpolation (Hardware-Assisted)\n\nOptimize shader interpolation path to utilize the hardware edge-equation signals for perspective-correct barycentric weights. [Grant Task 5]\n\n"
        
        # Renumber the steps 40-45 to 33-38. 
        phase_5_text_cleaned = phase_5_text_cleaned.replace('### Step 40:', '### Step 33:')
        phase_5_text_cleaned = phase_5_text_cleaned.replace('### Step 41:', '### Step 34:')
        phase_5_text_cleaned = phase_5_text_cleaned.replace('### Step 42:', '### Step 35:')
        phase_5_text_cleaned = phase_5_text_cleaned.replace('### Step 43:', '### Step 36:')
        phase_5_text_cleaned = phase_5_text_cleaned.replace('### Step 44:', '### Step 37:')
        phase_5_text_cleaned = phase_5_text_cleaned.replace('### Step 45:', '### Step 38:')
        
        # Add [Grant Task 5] to the steps 33-35
        phase_5_text_cleaned = phase_5_text_cleaned.replace('Bilinear Texture Filtering\n', 'Bilinear Texture Filtering [Grant Task 5]\n')
        phase_5_text_cleaned = phase_5_text_cleaned.replace('Hardware Z-Buffer & Atomic Depth Test\n', 'Hardware Z-Buffer & Atomic Depth Test [Grant Task 5]\n')
        phase_5_text_cleaned = phase_5_text_cleaned.replace('Framebuffer Alpha Blending\n', 'Framebuffer Alpha Blending [Grant Task 5]\n')

        # Insert it
        content = content[:phase_3_idx] + interpolation_step + phase_5_text_cleaned + content[phase_3_idx:]

# 3. Add grant task markers to other existing steps 
# Phase 3 has steps 30-34, we need to renumber them to 39-43.
content = content.replace('### Step 30: M Extension (Integer Multiply/Divide)', '### Step 39: M Extension (Integer Multiply/Divide) [Grant Task 6]')
content = content.replace('### Step 31: A Extension (Atomics)', '### Step 40: A Extension (Atomics) [Grant Task 6]')
content = content.replace('### Step 32: Boot no-MMU Linux', '### Step 41: Boot no-MMU Linux [Grant Task 6]')
content = content.replace('### Step 33: MMU (Sv32)', '### Step 42: MMU (Sv32) [Grant Task 6]')
content = content.replace('### Step 34: Boot Full Linux', '### Step 43: Boot Full Linux [Grant Task 6]')

# Phase 4 has steps 35-39, we need to renumber them to 44-48.
content = content.replace('### Step 35: Minimal `vk_device` + `wsi_headless`', '### Step 44: Minimal `vk_device` + `wsi_headless` [Grant Task 7]')
content = content.replace('### Step 36: Shader Compiler (NIR → SPIR-B)', '### Step 45: Shader Compiler (NIR → SPIR-B) [Grant Task 7]')
content = content.replace('### Step 37: Draw Path (`vkCmdDraw`)', '### Step 46: Draw Path (`vkCmdDraw`) [Grant Task 7]')
content = content.replace('### Step 38: Texture Sampling (Software)', '### Step 47: Texture Sampling (Software) [Grant Task 7]')
content = content.replace('### Step 39: Vulkan CTS Subset', '### Step 48: Vulkan CTS Subset [Grant Task 7]')

# Also add the [Grant Task X] to step 31
content = content.replace('Extend Step 30 to process a list of triangle descriptors from PSRAM without\nCPU involvement.', 'Extend Step 30 to process a list of triangle descriptors from PSRAM without\nCPU involvement. [Grant Task 5 - Full Autonomous Pipeline]')

with open('docs/A0_roadmap.md', 'w') as f:
    f.write(content)

print("Roadmap fixed!")
