# Borg GPU — Register Architecture & Uniform Storage

**A Design Document for the Borg FP16 Shader Processor**

Andreas Wendleder — April 2026

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [The Problem: Shader State Clobbering](#2-the-problem-shader-state-clobbering)
3. [Design Constraints](#3-design-constraints)
4. [Survey of GPU Register Architectures](#4-survey-of-gpu-register-architectures)
   - 4.1 ARM Mali Utgard
   - 4.2 ARM Mali Midgard
   - 4.3 ARM Mali Bifrost & Valhall
   - 4.4 Imagination PowerVR SGX
   - 4.5 Broadcom VideoCore IV
   - 4.6 Qualcomm Adreno
   - 4.7 Open Source & Academic GPUs
5. [The Universal Pattern: Separate Uniform Storage](#5-the-universal-pattern-separate-uniform-storage)
6. [Design Options for Borg](#6-design-options-for-borg)
   - 6.1 Option A: 64 GPR Expansion
   - 6.2 Option B: Separate Uniform Buffer
   - 6.3 Option C: Fixed-Function Interpolation
   - 6.4 Option D: Multi-Pass Fragment Shading
7. [The Chosen Design: Uniform Buffer via funct3](#7-the-chosen-design-uniform-buffer-via-funct3)
8. [Hardware Implementation](#8-hardware-implementation)
9. [Impact on the Borg Roadmap](#9-impact-on-the-borg-roadmap)
10. [ASIC Area Analysis](#10-asic-area-analysis)
11. [Vulkan Compatibility](#11-vulkan-compatibility)
12. [Future Considerations](#12-future-considerations)
13. [Conclusion](#13-conclusion)

---

## 1. Executive Summary

The Borg GPU is a tiny FP16 shader processor designed for tapeout on
Tiny Tapeout's IHP SG13G2 130nm shuttle. Its distinguishing feature is
hardware-autonomous shader chaining: the rasterizer evaluates edge
functions, and — for inside pixels — automatically triggers the
fragment shader, all without CPU intervention.

This document addresses a critical architectural decision at step 10.6.4
of Borg's development roadmap: how to solve **register state clobbering**
between the rasterizer and fragment shaders when they share a single
32-entry register file.

The original plan was to expand the register file from 32 to 64 entries,
following the trajectory of ARM Mali Bifrost. After analyzing the
constraints of ASIC tapeout (area), FPGA prototyping (iCE40 BRAMs),
Vulkan compatibility, and RISC-V instruction encoding, we instead adopt
a **separate uniform buffer** — the approach used by virtually every
shipping GPU architecture, from the Broadcom VideoCore IV in the
Raspberry Pi to Qualcomm's Adreno in modern smartphones.

The uniform buffer adds approximately 512 flip-flops on ASIC (versus
1,536 for the 64 GPR expansion), preserves the existing 32-register
RISC-V-compatible instruction encoding, and naturally maps to Vulkan's
`VkDescriptorType` uniform buffer objects.

---

## 2. The Problem: Shader State Clobbering

### 2.1 Background

Borg's rendering pipeline chains two shader programs automatically:

1. **Rasterizer shader** (`rasterize.s`): Evaluates three edge functions
   per pixel to determine inside/outside status. Writes results to
   registers r0, r1, r2.

2. **Fragment shader** (`shader.frag`): For inside pixels, interpolates
   up to six vertex attributes (R, G, B, Z, U, V) using barycentric
   coordinates derived from the edge function outputs.

Both shaders execute on the same FPU pipeline and share the same
32-entry register file. The hardware chaining FSM (step 10.6.2)
automatically triggers the fragment shader after the rasterizer
completes, provided the pixel is inside the triangle.

### 2.2 The Register Budget Problem

Each shader needs its own set of **uniform registers** — values that
are loaded once per triangle and must persist across all pixel
invocations:

| Shader | Uniforms | I/O | Temporaries | Total |
|--------|----------|-----|-------------|-------|
| `rasterize.s` | 12 (edge constants, negated vertex positions) | 3 outputs (e0, e1, e2) + 2 hw inputs (r30, r31) | ~5 | 17 |
| `shader.frag` | 19 (18 vertex colors + inv_area) | 3 inputs (e0, e1, e2) + 6 outputs (R,G,B,Z,U,V) | ~3 | 29 |

The fragment shader alone consumes 29 of 30 usable registers (r0–r29,
with r30/r31 reserved for the hardware coordinate LUT). When chained
with the rasterizer, **both sets of uniforms must coexist**, because
the rasterizer's uniforms must persist across pixel invocations — the
CPU loads them once per triangle, not once per pixel.

Combined uniform count: 12 + 19 = **31 uniforms alone**, exceeding the
30 usable GPRs before even counting temporaries or I/O.

### 2.3 Why This Matters

Without solving this, Borg cannot achieve autonomous shader chaining
(step 10.6.5). The CPU would need to reload uniforms between shader
invocations, defeating the entire purpose of hardware autonomy —
the key transition from "ALU co-processor" to "rasterizer" that
defines Phase 2 of the project.

---

## 3. Design Constraints

Any solution must satisfy four simultaneous constraints:

### 3.1 ASIC Tapeout (Tiny Tapeout IHP SG13G2)

- **Process**: IHP SG13G2, 130nm BiCMOS
- **Target**: 4×3 to 4×5 tiles (715€ to 1,115€)
- **Area matters**: Every flip-flop counts. The register file is
  currently triplicated (3 copies for 3 simultaneous read ports),
  making it one of the largest structures in the design.
- **No multi-port SRAM macros**: Standard cells or single-port SRAM
  only through the OpenROAD flow.

Current register file cost:
- 32 registers × 16 bits × 3 copies = **1,536 flip-flops**

Expanding to 64 registers:
- 64 registers × 16 bits × 3 copies = **3,072 flip-flops** (+100%)

### 3.2 FPGA Prototyping (pico-ice, iCE40 UP5K)

- **LUTs**: 5,280
- **BRAMs**: 30 (each 256×16-bit, single-port with 1R + 1W)
- **DSP**: 8 blocks

The register file currently uses 3 BRAMs (one per copy). Each BRAM
holds 256×16-bit entries, so both 32 and 64 registers fit without
additional BRAMs. However, a separate uniform buffer would consume
one additional BRAM (4 total out of 30), which is acceptable.

### 3.3 RISC-V Compatibility

Borg currently uses standard RISC-V R-type and R4-type instruction
encoding for its floating-point operations:

```
R-type:   [31:25] funct7  [24:20] rs2  [19:15] rs1  [14:12] funct3  [11:7] rd   [6:0] opcode
R4-type:  [31:27] rs3 [26:25] funct2   [24:20] rs2  [19:15] rs1  [14:12] funct3  [11:7] rd   [6:0] opcode
```

The register fields are 5 bits wide, addressing 32 registers (f0–f31).
This matches the RISC-V F-extension specification. Expanding to 64
registers would require 6-bit fields, breaking RISC-V compatibility
and requiring a fully custom instruction format.

RISC-V compatibility is a nice-to-have, not a hard requirement. But
preserving it has genuine practical value:

- The TinyQV CPU (which drives the GPU) is itself RISC-V.
- Future integration with standard toolchains (GCC, LLVM) becomes
  easier if the shader ISA resembles standard RISC-V.
- The Mesa/NIR shader compiler backend (Phase 4, step 23) benefits
  from a familiar encoding.

### 3.4 Vulkan Compatibility

Phase 4 of the roadmap targets a Mesa Vulkan ICD. The Vulkan
specification defines several mechanisms for passing constant data
to shaders:

- **Push constants**: Minimum 128 bytes, stored inline in command
  buffers. Very fast, very small.
- **Uniform buffer objects (UBOs)**: Minimum 16 KB range, read-only.
  Designed for per-draw-call constants.
- **Storage buffer objects (SSBOs)**: Read-write, larger capacity.

Vulkan does *not* expose a "shader register count" — this is an
implementation detail handled by the driver's shader compiler. The
driver's NIR backend maps Vulkan's `nir_intrinsic_load_uniform` and
`nir_intrinsic_load_push_constant` operations to whatever hardware
mechanism is available. A separate uniform buffer maps naturally
to these intrinsics.

---

## 4. Survey of GPU Register Architectures

To inform the Borg design decision, this section surveys how
commercial and academic GPU architectures handle uniform storage,
register files, and shader-stage isolation. A recurring pattern
emerges: **every architecture separates uniforms from GPRs**.

### 4.1 ARM Mali Utgard (Mali-200, Mali-300, Mali-400)

**Era**: 2007–2014
**Process**: 65nm–28nm
**Market**: Low-end smartphones, feature phones

The Utgard architecture took the simplest possible approach to
shader-stage isolation: **separate processors**.

- **Vertex Processor (VP)**: Dedicated hardware for vertex shading.
  Had its own register file, instruction memory, and FPU.
- **Fragment Processor (FP)**: Separate, independent hardware for
  fragment shading. Multiple FPs could be instantiated for
  throughput (Mali-400 MP4 had 4 fragment processors).

Since the processors were physically separate, there was no register
clobbering problem. Each had its own register file and its own
uniform storage. The drawback was inflexibility — if a workload was
vertex-heavy, the fragment processors sat idle, and vice versa.

**Uniform handling**: Uniforms were loaded into dedicated constant
registers within each processor. The separation between vertex and
fragment state was architectural, not a software convention.

**Relevance to Borg**: Borg's single-core design cannot use this
approach. But Utgard demonstrates that the simplest GPUs in history
never put uniforms in the same register file as temporaries.

### 4.2 ARM Mali Midgard (Mali-T600, T700, T800 series)

**Era**: 2012–2018
**Process**: 28nm–16nm
**Market**: Mid-range to flagship smartphones

Midgard introduced ARM's first **unified shader architecture** — a
single shader core type that could execute vertex, fragment, or
compute work. This was a major step forward in flexibility but
required solving the register sharing problem.

Key architectural features:

- **Unified register file**: Shared across threads executing on the
  core. The register file was partitioned dynamically based on shader
  requirements.
- **Thread-count/register tradeoff**: A shader using fewer registers
  allowed more concurrent threads, improving latency hiding. A
  register-heavy shader reduced parallelism.
- **Tripipe execution**: Three parallel pipelines (arithmetic,
  load/store, texture) operated simultaneously, each reading from
  the shared register file.
- **SIMD width**: 128-bit, supporting 8×FP16, 4×FP32, or 2×FP64
  operations per cycle.

**Uniform handling**: Midgard stored uniforms in a fast on-chip memory
area accessible via the load/store pipeline. The shader compiler
"promoted" small uniform arrays into registers for performance, but
the primary uniform storage was not in the GPR file.

**Register file size**: The Midgard register file was significantly
larger than 32 entries — typically 256 registers per core, shared
across 4–16 concurrent threads. Each thread saw a window of the
physical register file.

**Relevance to Borg**: Midgard's register windowing is too complex
for Borg's single-thread design. But its separation of uniform
storage from GPRs confirms the universal pattern.

### 4.3 ARM Mali Bifrost & Valhall

**Era**: 2016–present
**Process**: 16nm–5nm
**Market**: Flagship smartphones, Chromebooks, automotive

Bifrost was referenced in Borg's original roadmap (step 10.6.4) as
the inspiration for expanding to 64 registers. Let's examine what
Bifrost actually did and whether it's the right model for Borg.

Key architectural features:

- **Quad execution model**: Unlike the 32-wide warps of NVIDIA or
  the 64-wide wavefronts of AMD, Bifrost groups just **4 scalar
  threads** into a "quad." This is relevant because it means Bifrost
  operates at a much smaller granularity than desktop GPUs.
- **Scalar ISA**: Unlike Midgard's SIMD instructions, Bifrost uses
  scalar instructions. The hardware implicitly executes them across
  the 4 threads of a quad.
- **64 registers per thread** (32-bit each, Mali-G71): This is the
  number that inspired Borg's 64-register plan.
- **Claused shaders**: Instructions are grouped into "clauses" that
  execute atomically, with explicit scoreboarding between clauses.

**Uniform handling**: Bifrost includes **fast constant storage**
specifically for OpenGL ES uniforms and Vulkan push constants. The
shader compiler can "promote" uniform data into constant registers
that are free to access (no per-thread load instructions needed).
This is separate from the GPR file.

Valhall (the successor to Bifrost) continued this design, increasing
the register file further as transistor budgets grew.

**Why Bifrost's 64 registers aren't the right model for Borg**:

Bifrost's 64 registers serve a different purpose than what Borg needs.
In Bifrost, the large register file exists to:
1. Support complex programmable shaders (user-written GLSL/HLSL)
2. Reduce register spilling to memory (latency hiding)
3. Trade off against thread occupancy (more registers = fewer threads)

Borg doesn't have multi-threading, doesn't do latency hiding, and
its shaders are simple enough to fit in 16 GPRs if uniforms are
external. The 64-register expansion would solve the clobbering
problem, but it's using a sledgehammer where a scalpel suffices.

More importantly, Bifrost's 64 registers are at **5nm–16nm**. On
Borg's 130nm IHP process, the area cost is orders of magnitude
higher per transistor. What's "free" at 5nm is expensive at 130nm.

### 4.4 Imagination PowerVR SGX

**Era**: 2003–2014
**Process**: 130nm–28nm
**Market**: iPhones (A4–A7), iPod Touch, low-end Android

PowerVR SGX is particularly relevant to Borg because it operated at
similar transistor budgets and was designed for extreme power
efficiency in mobile devices.

Key architectural features:

- **Tile-Based Deferred Rendering (TBDR)**: The defining feature of
  PowerVR. Geometry is first binned into screen-space tiles. Then,
  for each tile, Hidden Surface Removal (HSR) eliminates occluded
  fragments **before** shading. Only visible pixels are shaded.
- **Unified Scalable Shader Engine (USSE)**: A unified shader
  processor that executes vertex, fragment, and compute work.
- **Multiple register types**:
  - **Temporary registers (`Rn`)**: Per-thread general-purpose
    registers for computation.
  - **Shared registers (`SHn`)**: Uniform data shared across all
    threads of a draw call. Loaded once, read many times.
  - **Coefficient registers (`CFn`)**: Interpolation coefficients
    for varying inputs.
  - **Output registers (`On`)**: Fragment shader outputs.
- **Common Store**: A shared on-chip memory pool from which
  temporary, shared, and coefficient registers were allocated.

**Uniform handling**: Uniforms were explicitly mapped to "shared
registers" (`SHn`), which were architecturally distinct from the
temporary registers used for computation. The shader compiler emitted
different instruction encodings for accessing shared versus temporary
registers. This is exactly the pattern Borg should follow.

**Why PowerVR SGX matters for Borg**: SGX operated at 130nm (SGX520,
2004) through 28nm (SGX554, 2012) — the same process generation as
Borg's IHP tapeout. Its register architecture was designed for exactly
the constraints Borg faces: minimal area, low power, tile-based
rendering, and separate uniform storage. SGX proved that you don't
need 64 GPRs to build a functional GPU — you need a clean separation
between uniforms and temporaries.

### 4.5 Broadcom VideoCore IV

**Era**: 2012–present
**Process**: 40nm
**Market**: Raspberry Pi (all models through Pi 3)

VideoCore IV is the most relevant comparison for Borg because it is:
- Fully documented (Broadcom released the architecture spec)
- Extremely area-efficient
- Used in a real product (Raspberry Pi)
- Has an open-source driver stack (Mesa vc4)

Key architectural features:

- **QPU (Quad Processing Unit)**: 16 QPUs per GPU, each processing
  4 elements in SIMD fashion.
- **Two register files per QPU**:
  - **Regfile A**: 32 entries × 32-bit
  - **Regfile B**: 32 entries × 32-bit
  - Each instruction can read one operand from A and one from B
    (with restrictions on same-file reads).
- **Accumulators**: 4 dedicated accumulator registers (r0–r3) that
  are separate from the main register files.

**Uniform handling** — and this is the key insight:

VideoCore IV handles uniforms through a **streaming uniform FIFO**.
There is a dedicated read-only register address (`unif`) that, when
read, returns the **next value from a sequential uniform stream**
stored in memory. The hardware automatically advances a pointer.

This is an extraordinarily area-efficient design:
- No on-chip uniform storage needed (uniforms stream from memory)
- No uniform register file (just a small FIFO for prefetching)
- The register files are entirely free for computation
- The shader compiler ensures uniform reads happen in the correct
  order

For Borg, this streaming approach is too complex (it requires DMA from
memory), but the principle is sound: **uniforms should not consume GPR
slots**.

### 4.6 Qualcomm Adreno

**Era**: 2009–present
**Process**: 28nm–3nm
**Market**: Snapdragon SoCs, smartphones, XR headsets

Adreno GPUs power more Android devices than any other GPU. Their
architecture emphasizes bandwidth efficiency and power savings.

Key architectural features:

- **Tiled rendering** with on-chip GMEM (Graphics Memory, an SRAM
  scratchpad for the current tile's color and depth buffers).
- **FlexRender**: Can dynamically switch between tiled and immediate
  rendering modes.
- **Unified shader cores** with scalar ALU execution.

**Uniform handling**: Adreno includes **dedicated constant RAM**
on-chip, separate from the GPR register file. This RAM is optimized
for broadcast access (all threads reading the same value) and provides
low-latency access to uniform data without consuming register file
entries.

The Adreno driver maps Vulkan uniform buffers and push constants to
this constant RAM. If the constant data exceeds the on-chip capacity,
it falls back to cached memory access.

**Relevance to Borg**: Adreno's constant RAM is conceptually identical
to the uniform buffer proposed for Borg. The key insight is that
dedicated constant storage is a universal pattern in mobile GPU design,
not an exotic optimization.

### 4.7 Open Source & Academic GPUs

Several open-source GPU projects have explored minimal shader
processor designs:

**MiaowGPU**: An open-source implementation of a subset of the AMD
Southern Islands (GCN) ISA. Uses a large register file (256 entries
per SIMD lane) with separate scalar and vector register files. The
scalar register file handles uniform-like data. Demonstrates the
GPR/uniform split even in an academic context.

**MIAOW Register Architecture**:
- Vector GPRs: 256 × 64-bit per SIMD lane
- Scalar GPRs: 512 × 32-bit (shared, for uniform-like data)

**Nyuzi Processor**: An open-source GPGPU with 32 vector registers
per thread and a separate constant data path. Implements tile-based
rendering in software.

**VeriGPU**: A Verilog GPU implementation targeting FPGA, with a
minimal register file and dedicated uniform storage.

The pattern is universal: even the smallest, most minimal GPU designs
separate uniform storage from general-purpose registers.

---

## 5. The Universal Pattern: Separate Uniform Storage

Across every GPU architecture surveyed — from the 130nm PowerVR SGX520
to the 3nm Adreno 750 — a single pattern emerges:

> **Uniforms are never stored in the GPR file.**

The specific implementation varies:
- PowerVR: Shared registers (`SHn`)
- VideoCore IV: Streaming FIFO (`unif`)
- Mali Bifrost: Fast constant storage
- Adreno: Constant RAM
- AMD GCN: Scalar GPR file
- NVIDIA: Constant memory (`c[]`)

But the architectural principle is always the same: data that is
constant across shader invocations belongs in a separate, typically
read-only, storage class. This separation provides multiple benefits:

1. **Area efficiency**: Uniforms don't need multi-ported storage
   (GPR files are multi-ported for ALU operand reads; uniforms
   typically need only one read port).

2. **No clobbering**: Different shader stages can maintain their
   own uniform sets without conflict.

3. **Natural API mapping**: Graphics APIs (OpenGL, Vulkan, DirectX)
   all distinguish between uniform/constant data and per-thread
   variables. A hardware uniform buffer maps directly to the API
   concept.

4. **Power efficiency**: Uniform reads are broadcast operations
   (same value for all pixels). Dedicated storage can be optimized
   for this access pattern.

Borg's original plan to expand to 64 GPRs was solving the right
problem (clobbering) with the wrong tool (more GPRs). Every GPU in
history says: add a uniform buffer.

---

## 6. Design Options for Borg

With the constraint analysis and GPU survey as background, here
are the options considered for Borg, with their tradeoffs.

### 6.1 Option A: 64 GPR Expansion (Original Plan)

**Description**: Expand the register file from 32 to 64 entries.
Widen all instruction register fields from 5 to 6 bits. Abandon
RISC-V compatibility.

**ASIC cost**: +1,536 flip-flops (64×3×16 − 32×3×16 = 3,072 − 1,536)

**FPGA cost**: 0 additional BRAMs (64 entries still fits in 256-entry
iCE40 BRAMs)

**Pros**:
- Simple programming model (everything is a register)
- No new memory structures

**Cons**:
- Doubles register file area on ASIC (+100%)
- Breaks RISC-V instruction encoding (6-bit fields required)
- Custom instruction format needed
- MMIO address space overflow (64×4 + 64×4 = 512 bytes, no room
  for control registers in the 9-bit address space)
- Treats a constant-data problem as a register-count problem

**Verdict**: ❌ Rejected. Expensive on ASIC, breaks RISC-V, and
architecturally incorrect.

### 6.2 Option B: Separate Uniform Buffer (Chosen)

**Description**: Add a small uniform buffer (32 entries × 16-bit)
as a separate read-only memory. Access via unused funct3 bits in
the RISC-V instruction encoding.

**ASIC cost**: +512 flip-flops (for a 32×16-bit register-based
memory with combinational reads)

**FPGA cost**: +1 BRAM (if using SyncReadMem; or ~512 LUTs if
register-based)

**Pros**:
- Minimal area cost (+33% of current register file, vs +100% for
  Option A)
- Preserves RISC-V instruction encoding
- Natural Vulkan mapping (UBOs → uniform buffer)
- Solves clobbering cleanly (uniforms persist, GPRs are free)
- Only 1 read port needed (at most 1 uniform per instruction)

**Cons**:
- New hardware component (SyncReadMem + read mux)
- Shader compiler must track uniform vs GPR operands
- funct3 field repurposed (breaks strict RISC-V F-extension
  semantics, but Borg already ignores rounding modes)

**Verdict**: ✅ Chosen. Best balance of area, compatibility, and
architectural correctness.

### 6.3 Option C: Fixed-Function Interpolation

**Description**: Replace the programmable fragment shader with
a hardware interpolation FSM that sequences FMA operations
automatically, reading vertex attributes from a dedicated buffer.

**ASIC cost**: ~300 flip-flops (FSM + attribute buffer)

**Pros**:
- Smallest area cost
- No ISA changes
- Vulkan compatible (interpolation is implementation-defined)

**Cons**:
- Removes fragment shader programmability (limitable for Vulkan
  user shaders in Phase 4)
- The user's fragment shader (from GLSL/HLSL) must still run
  somewhere — this only offloads the interpolation, not the
  entire fragment stage
- Would need to be combined with Option B anyway for the user
  shader's uniforms

**Verdict**: ⚠️ Interesting for the future (could be added as an
optimization later), but doesn't eliminate the need for a uniform
buffer.

### 6.4 Option D: Multi-Pass Fragment Shading

**Description**: Process one or two interpolation channels at a
time in separate shader passes, reloading different vertex color
uniforms between passes.

**ASIC cost**: 0 (no hardware changes)

**Pros**:
- Zero hardware cost
- No ISA changes

**Cons**:
- 3–6× slower fragment shading (multiple passes per pixel)
- Requires the chaining FSM to manage multiple passes, adding
  FSM complexity
- Re-introduces CPU involvement for uniform reloading between
  passes (defeats the purpose of hardware autonomy)

**Verdict**: ❌ Rejected. Defeats the purpose of autonomous
shader chaining.

---

## 7. The Chosen Design: Uniform Buffer via funct3

### 7.1 Instruction Encoding

The existing RISC-V R-type and R4-type instruction formats have
unused bits in the `funct3` and `funct2` fields. Borg currently
hardwires `funct3` to 000 (round-to-nearest-even) and `funct2`
to 00. These fields are available for extension.

We use 2 bits from `funct3` to encode which operand (if any)
reads from the uniform buffer instead of the GPR file:

```
funct3[1:0] encoding:
  00 = All operands from GPR file (default, backward compatible)
  01 = rs1 reads from uniform buffer
  10 = rs2 reads from uniform buffer
  11 = rs3 reads from uniform buffer
```

The 5-bit register index is reused to address the uniform buffer
(32 entries, same as GPR file size). This means:

- No new instruction format
- No wider register fields
- Backward compatible (funct3 = 000 behaves identically to today)
- RISC-V base encoding preserved (the funct3 extension is a
  Borg-specific ISA extension, similar to how RISC-V vendors
  use custom opcode spaces)

### 7.2 Why At Most One Uniform Per Instruction

A critical observation makes this design practical: **in every Borg
shader instruction, at most one operand is a uniform**.

Rasterizer shader (`rasterize.s`):
```asm
fadd.s  f_dpx, f_r30, f_neg_vx    # rs1=coordLut, rs2=UNIFORM, rs3=n/a
fmul.s  f_e0,  f_dx0, f_dpy       # rs1=UNIFORM,  rs2=GPR,     rs3=n/a
fmadd.s f_e0,  f_ndy, f_dpx, f_e0 # rs1=UNIFORM,  rs2=GPR,     rs3=GPR
```

Fragment shader (`shader.frag`):
```asm
fmul.s  r_ch, r_e0, u_c0          # rs1=GPR, rs2=UNIFORM, rs3=n/a
fmadd.s r_ch, r_e1, u_c1, r_ch    # rs1=GPR, rs2=UNIFORM, rs3=GPR
fmul.s  r_ch, r_ch, u_inv_area    # rs1=GPR, rs2=UNIFORM, rs3=n/a
```

This is not coincidental — it's inherent to the mathematical
structure of rasterization and interpolation. Edge functions
multiply a per-pixel value (GPR) by a per-triangle constant
(uniform). Barycentric interpolation does the same. You never
multiply two uniforms together (that's a constant, computable
at triangle setup time).

This means the uniform buffer needs only **one read port**,
regardless of how many ALU operands an instruction has. On FPGA,
one read port = one BRAM (no triplication needed). On ASIC, a
single-port memory is far smaller than a multi-port one.

### 7.3 Register Budget After the Change

With uniforms externalized to the buffer:

**Rasterizer shader**:
| Resource | Count | Location |
|----------|-------|----------|
| Outputs (e0, e1, e2) | 3 | GPR: r0, r1, r2 |
| Temporaries (dpx, dpy, reused) | ~5 | GPR: r3–r7 |
| Edge constants (dx, neg_dy × 3) | 6 | Uniform buffer |
| Negated vertices (neg_vx, neg_vy × 3) | 6 | Uniform buffer |
| Hardware pixel coords | 2 | r30/r31 (coordLut) |
| **Total GPR** | **8** | |
| **Total uniform** | **12** | |

**Fragment shader**:
| Resource | Count | Location |
|----------|-------|----------|
| Inputs (e0, e1, e2) | 3 | GPR: r0, r1, r2 (from rasterizer) |
| Outputs (R, G, B, Z, U, V) | 6 | GPR: r3–r8 |
| Temporaries | ~4 | GPR: r9–r12 |
| Vertex colors (c0, c1, c2 × 6 channels) | 18 | Uniform buffer |
| inv_area | 1 | Uniform buffer |
| **Total GPR** | **13** | |
| **Total uniform** | **19** | |

**Combined (both shaders coexisting)**:
- GPR usage: 8 + 13 = ~16 (with overlap at r0–r2)
- Uniform usage: 12 + 19 = 31

Both fit comfortably. The 32-entry uniform buffer accommodates both
shader stages' uniforms simultaneously (31 of 32 entries used).
The GPR file has ample room at 16 of 30 entries.

---

## 8. Hardware Implementation

### 8.1 New Components

**Uniform buffer**: A 32-entry × 16-bit memory. Implementation
depends on target:

- **iCE40 FPGA**: One `SyncReadMem(32, UInt(16.W))` = one BRAM.
  Single read port, single write port. Since we need only one read
  per cycle (one uniform operand per instruction), one BRAM suffices.

- **IHP SG13G2 ASIC**: For minimum area, implement as a register-based
  memory (`Vec(32, Reg(UInt(16.W)))`) = 512 flip-flops. This gives
  combinational reads (no cycle latency) at the cost of area. For
  larger implementations, a synthesized single-port SRAM could be
  used instead.

**Read mux**: A 3-way mux that steers the uniform buffer's read
data to the correct operand slot (rs1, rs2, or rs3) based on the
funct3 decode.

**MMIO interface**: A new address range (`BORG_UNIFORM_OFFSET`) for
the CPU to write uniform values. This fits easily in the existing
9-bit address space (the uniform buffer occupies 32 × 4 = 128 bytes
if word-addressed, or 32 × 2 = 64 bytes if half-word addressed).

### 8.2 Pipeline Integration

The uniform buffer read integrates into the existing pipeline at
the operand-resolution stage. Currently, each port (A, B, C) has
a mux that selects between the register file output and the
coordLut injection for r30/r31:

```
resolved_data = Mux(idx === 30, coordLut(iterX),
                Mux(idx === 31, coordLut(iterY),
                regFile.readData))
```

The uniform buffer adds one more mux input:

```
resolved_data = Mux(idx === 30, coordLut(iterX),
                Mux(idx === 31, coordLut(iterY),
                Mux(this_port_is_uniform, uniformBuf(idx),
                regFile.readData)))
```

The `this_port_is_uniform` signal is derived from `funct3[1:0]`:
port A uses uniform when `funct3 == 01`, port B when `funct3 == 10`,
port C when `funct3 == 11`.

### 8.3 MMIO Address Map

The uniform buffer fits in the existing address space without
reshuffling:

| Region | Offset | Size | Description |
|--------|--------|------|-------------|
| Registers (r0–r31) | 0 | 128 bytes | GPR file (32 × 4) |
| IMEM | 128 | 256 bytes | 64 instruction slots |
| Iterator BBOX | 384 | 4 bytes | Bounding box |
| Iterator | 388 | 4 bytes | Advance / read position |
| Control | 392 | 4 bytes | Start / reset / status |
| Frag PC | 396 | 4 bytes | Fragment shader start PC |
| **Uniform buffer** | **400** | **128 bytes** | **32 × 4 uniform slots** |

Total: 528 bytes. Exceeds 512... so we can either:
1. Use 2-byte addressing for uniforms (32 × 2 = 64 bytes → total 464)
2. Reduce IMEM from 64 to 48 slots (saves 64 bytes → total 464)
3. Expand to 10-bit address space (requires bus decoder change)

Option 1 is cleanest: since uniforms are FP16 values (16 bits), there's
no need for 32-bit word addressing. The CPU writes 16-bit values
directly.

---

## 9. Impact on the Borg Roadmap

### 9.1 Modified Step 10.6.4

The original three sub-steps of 10.6.4 are replaced:

**Original**:
- 10.6.4.1: Hardware 6-bit expansion (BF_RS1-RS3-RD 5→6 bits)
- 10.6.4.2: Compiler 6-bit expansion (instruction encoding)
- 10.6.4.3: Shader reallocation (rebuild shaders)

**Revised**:
- 10.6.4.1: **Hardware uniform buffer** — Add 32-entry SyncReadMem,
  decode funct3[1:0], integrate read mux into pipeline, add MMIO
  write interface
- 10.6.4.2: **Compiler uniform support** — Update `borg_backend.py`
  to emit funct3 bits for uniform operands. Update
  `Instructions.scala` encoding functions.
- 10.6.4.3: **Shader reallocation** — Rebuild `rasterize.s` and
  `shader.frag` using the new uniform buffer. Verify pixel-perfect
  against golden image.

### 9.2 Step 10.6.5: Firmware Auto-Chain Integration

This step is unchanged but becomes easier: with uniforms in a separate
buffer, the CPU loads both shader stages' uniforms once per triangle.
The hardware FSM chains rasterizer → fragment without any register
conflicts. The hot loop is:

```c
// Per pixel (CPU only reads results, no register management)
BORG_ITER = 1;                    // advance iterator
uint32_t iter = BORG_ITER;        // read position + inside flag
if (BORG_ITER_INSIDE(iter)) {
    uint16_t r = BORG_REG(3);     // read interpolated R
    uint16_t g = BORG_REG(4);     // read interpolated G
    // ... write to framebuffer
}
```

### 9.3 Impact on Phase 4 (Mesa Vulkan Driver)

The uniform buffer maps directly to Vulkan's uniform buffer concept:

- **Step 23 (NIR → SPIR-B compiler)**: The NIR backend emits
  `nir_intrinsic_load_uniform` as instructions with funct3 flags.
  This is a natural mapping — NIR already distinguishes uniform
  loads from register operations.

- **Step 22 (vk_device)**: The driver allocates uniform data in
  the Borg uniform buffer via MMIO writes, matching the standard
  Vulkan flow of mapping UBOs.

- **Push constants** (128 bytes minimum per Vulkan spec): Map directly
  to the 32-entry uniform buffer (32 × 2 bytes = 64 bytes of push
  constant capacity). This exceeds the needs of simple shaders and
  can be extended later.

### 9.4 Impact on Phase 5 (GPU Hardware Extensions)

- **Step 28 (Memory Load/Store from Shader)**: When shaders can
  access memory directly, the uniform buffer can serve as a fast
  L0 cache for frequently accessed constants, reducing memory
  traffic.

- **Step 30 (Multi-Lane SIMD)**: The uniform buffer naturally
  broadcasts to all SIMD lanes (same value for all pixels). No
  per-lane replication needed.

---

## 10. ASIC Area Analysis

### 10.1 Current Design

| Component | Entries | Copies | Bits | Flip-flops |
|-----------|---------|--------|------|------------|
| GPR file (FP16) | 32 | 3 | 16 | 1,536 |
| IMEM | 64 | 1 | 32 | 2,048 |
| Coord LUT | 64 | 1 | 16 | 1,024 |
| Control regs | ~10 | 1 | various | ~200 |
| **Total storage** | | | | **~4,808** |

### 10.2 Option A: 64 GPR Expansion

| Component | Entries | Copies | Bits | Flip-flops | Delta |
|-----------|---------|--------|------|------------|-------|
| GPR file (FP16) | **64** | 3 | 16 | **3,072** | +1,536 |
| IMEM | 48* | 1 | 32 | 1,536 | −512 |
| Coord LUT | 64 | 1 | 16 | 1,024 | 0 |
| Control regs | ~10 | 1 | various | ~200 | 0 |
| **Total storage** | | | | **~5,832** | **+1,024** |

*IMEM reduced to 48 to fit MMIO address space.

### 10.3 Option B: Uniform Buffer (Chosen)

| Component | Entries | Copies | Bits | Flip-flops | Delta |
|-----------|---------|--------|------|------------|-------|
| GPR file (FP16) | 32 | 3 | 16 | 1,536 | 0 |
| IMEM | 64 | 1 | 32 | 2,048 | 0 |
| Coord LUT | 64 | 1 | 16 | 1,024 | 0 |
| **Uniform buffer** | **32** | **1** | **16** | **512** | **+512** |
| Control regs | ~10 | 1 | various | ~200 | 0 |
| **Total storage** | | | | **~5,320** | **+512** |

### 10.4 Comparison

```
Current design:     4,808 FFs  [████████████████████         ]
+ Uniform buffer:   5,320 FFs  [██████████████████████       ] (+11%)
+ 64 GPR expansion: 5,832 FFs  [█████████████████████████    ] (+21%)
```

The uniform buffer approach adds 512 flip-flops (+11%) versus the
64 GPR expansion's 1,024 net flip-flops (+21%). On a budget of
4×3 tiles (12 tiles, each ~170×170 µm on IHP SG13G2), this
difference could matter.

### 10.5 Future Optimization: Eliminating Triplication

The GPR file's triplication (3 copies × 32 × 16 = 1,536 FFs) is a
legacy of the iCE40 FPGA's 1R1W BRAM constraint. On ASIC, there are
alternative approaches:

| Approach | GPR FFs | Additional cost |
|----------|---------|-----------------|
| 3 copies (current) | 1,536 | None |
| 2 copies + latch rs3 early | 1,024 | +1 cycle for FMA |
| 1 copy + time-muxed reads | 512 | +2-3 cycles CPI |

Eliminating triplication is a Phase 5 optimization. For the initial
tapeout, the proven 3-copy design is the safe choice. The uniform
buffer decision is orthogonal to and compatible with any future
triplication optimization.

---

## 11. Vulkan Compatibility

### 11.1 Vulkan's Uniform Data Model

Vulkan provides three mechanisms for passing constant data to shaders,
in order of increasing capacity and decreasing speed:

1. **Push constants** (128 bytes min): Inline in command buffers.
   Fastest access, smallest capacity.

2. **Uniform buffers (UBOs)** (16 KB min range): Read-only buffers
   bound via descriptor sets. The standard mechanism for per-draw
   uniform data (MVP matrices, material parameters, lights).

3. **Storage buffers (SSBOs)**: Read-write, much larger. For
   scene-wide data.

### 11.2 Mapping to Borg Hardware

| Vulkan concept | Borg hardware | Capacity |
|----------------|---------------|----------|
| Push constants | Uniform buffer (direct write) | 64 bytes (32 × FP16) |
| UBO (small) | Uniform buffer (driver loads) | 64 bytes |
| UBO (large) | Uniform buffer + memory fetch | limited by PSRAM |
| SSBO | PSRAM + memory load/store (Phase 5) | 8 MB |

For the simple shaders Borg targets (vertex transform, fragment
interpolation, color output), 32 uniform entries (64 bytes of FP16
data) is more than sufficient. A 4×4 MVP matrix requires 16 entries.
Per-vertex colors for a triangle require 18 entries. Both fit.

### 11.3 NIR Shader Compiler Integration

The Mesa NIR intermediate representation naturally separates uniform
data from temporaries:

```c
// NIR intrinsic for uniform access:
nir_intrinsic_load_uniform(offset)   // → Borg: read from uniform buffer

// NIR intrinsic for varying input (interpolated):
nir_intrinsic_load_input(slot)       // → Borg: read from GPR

// NIR ALU operation:
nir_alu_instr(add, src0, src1)       // → Borg: fadd.s rd, rs1, rs2
```

The Borg NIR backend (step 23) would lower `nir_intrinsic_load_uniform`
to instructions with the appropriate funct3 flag set. This is a
straightforward lowering, simpler than what most GPU NIR backends
handle.

---

## 12. Future Considerations

### 12.1 Uniform Buffer Capacity

32 entries may eventually be limiting for complex Vulkan shaders. If
needed, the buffer can be expanded:

- **To 64 entries**: doubles the memory cost (+512 FFs) but requires
  6-bit addressing. Could use funct2 bits for the extra address bit.
- **To 128+ entries**: at this point, switch to memory-mapped uniform
  access via the load/store path (Phase 5, step 28).

### 12.2 Constant Folding & Specialization

With a uniform buffer, the shader compiler can perform **constant
folding** at draw-call time: if a uniform is known at compile time
(e.g., a material always uses the same texture coordinate transform),
the compiler can bake it into the instruction stream, freeing a
uniform buffer slot.

### 12.3 Register File Compression

For Phase 5 tapeout (larger tile budget), the GPR file could be
compressed using half-precision packing: two FP16 values per 32-bit
register file entry. This halves the register file area and doubles
effective capacity, at the cost of packing/unpacking mux logic.

### 12.4 Thread-Level Parallelism

If Borg eventually supports multiple concurrent threads (for latency
hiding during texture fetches, step 14+), the uniform buffer
naturally supports this: uniforms are shared across all threads of
the same draw call. Only the GPR file needs per-thread partitioning.

### 12.5 Fixed-Function Interpolation Block

A dedicated interpolation FSM (Option C from section 6.3) could be
added as a future optimization. It would sequence the barycentric
interpolation FMAs automatically, reading vertex attributes from
the uniform buffer. This would:

- Reduce fragment shader IMEM usage
- Improve interpolation throughput (hardware-scheduled FMAs)
- Free GPR registers during interpolation
- Be fully transparent to the Vulkan driver (interpolation is
  implementation-defined)

This optimization is independent of the uniform buffer design and
can be added in Phase 5 without changing the ISA.

### 12.6 Considerations for the RISC-V CPU

The TinyQV RISC-V CPU that drives Borg could potentially share
the uniform buffer infrastructure. If the CPU needs to pass small
constant arrays to the GPU (e.g., bone matrices for skeletal
animation), the uniform buffer provides a natural, low-overhead
mechanism. The CPU writes to the buffer via MMIO, and the GPU
shader reads from it — no command buffer parsing needed.

This aligns with Sebastian Aaltonen's "No Graphics API" philosophy:
the simpler and more direct the CPU-to-GPU data path, the less
driver overhead is needed. The uniform buffer is a direct,
hardware-visible constant store — exactly the kind of transparent
interface Aaltonen advocates.

---

## 13. Conclusion

### The Decision

Borg will add a **32-entry × 16-bit uniform buffer** accessed via
the `funct3` field of the existing RISC-V instruction encoding.
This replaces the original plan to expand the GPR file from 32 to
64 entries.

### Why

1. **Every GPU architecture separates uniforms from GPRs.** From the
   130nm PowerVR SGX520 to the 3nm Adreno 750, this is the universal
   pattern. Borg should follow it.

2. **Area savings on ASIC.** The uniform buffer adds 512 flip-flops
   versus 1,536 for the 64 GPR expansion — a 3× reduction in
   additional area.

3. **RISC-V preservation.** The 5-bit register fields and standard
   R-type/R4-type encoding are preserved. The funct3 extension is
   a minimal, backward-compatible change.

4. **Vulkan alignment.** The uniform buffer maps directly to Vulkan's
   uniform buffer objects and push constants, simplifying the future
   Mesa driver.

5. **At most one uniform operand per instruction.** The mathematical
   structure of rasterization and interpolation guarantees this,
   making a single-ported uniform buffer sufficient.

### What Changes

| Item | Before | After |
|------|--------|-------|
| Register file | 32 GPRs (3 copies) | 32 GPRs (3 copies) — unchanged |
| Uniform storage | In GPR file | Separate 32-entry buffer (1 copy) |
| Instruction encoding | RISC-V R/R4-type, funct3=000 | Same, funct3[1:0] selects uniform operand |
| MMIO | Regs + IMEM + control | + Uniform buffer region |
| Shader compilation | All vregs → GPRs | Uniforms → uniform buffer, temps → GPRs |
| ASIC area | ~4,808 FFs | ~5,320 FFs (+11%) |

### What Stays the Same

- 32-entry GPR file with 3 BRAM copies (FPGA) / triplicated (ASIC)
- 64-entry IMEM
- 4-cycle FMA pipeline
- Hardware shader chaining FSM
- coordLut at r30/r31
- All existing tests and golden images

---

*This document represents the architectural rationale for Borg's
uniform buffer design. The implementation is tracked in the project
roadmap (docs/A0_roadmap.md) under step 10.6.4.*
