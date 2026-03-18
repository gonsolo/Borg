# Borg — An Open-Source GPU

## Table of Contents

0. [Introduction](00_introduction.md) — Motivation and project overview
1. [The Borg Shader Processor](01_shader_processor.md) — FP16 FMA, registers, instruction memory
2. [The Shader Compiler](02_compiler.md) — SPIR-V → pseudo-assembly → SPIR-B pipeline
3. [The Software Driver](03_software_driver.md) — Shader pipeline, z-buffer, texturing
4. [Running on an FPGA](04_fpga.md) — pico-ice build, host communication, PIO
5. [Generating the ASIC](05_asic.md) — RTL-to-GDS flow, configuration, verification

### Appendices

1. [Development Roadmap](A0_roadmap.md) — Phases, tile budget, hardware resources
2. [Gap Analysis and Vulkan Strategy](A2_gap_analysis.md) — vkcube, SuperTuxKart, "No Graphics API", Vulkan ICD plan
3. [The TinyQV CPU](A3_tinyqv_cpu.md) — Nibble-serial RV32I architecture
4. [Bibliography](A1_bibliography.md) — References and further reading
