# Architectural Tricks

Borg is designed with intense area constraints in mind. To fit a functional 3D GPU into a few thousand logic gates on an iCE40 FPGA or a Tiny Tapeout ASIC, the architecture heavily favors extreme reuse, algorithm simplification, and trading free resources (like unused memory blocks) for expensive logic.

Here are 15 hardware and software tricks Borg uses to stay tiny:

## 1. The "Everything is an FMA" Pipeline

Floating-point math is expensive. Instead of having separate hardware for addition (`fadd`) and multiplication (`fmul`), Borg has exactly **one** Floating-Point Fused Multiply-Add (FMA) pipeline.

* When you run `fadd r1, r2`, Borg actually executes `fma r1, 1.0, r2` under the hood.
* When you run `fmul r1, r2`, Borg actually executes `fma r1, r2, 0.0`.

This ensures only one FP-multiplier and one FP-adder are synthesized, heavily reusing them across clock cycles to keep the logic footprint tiny.

## 2. Half-Precision Math (FP16)

Borg uses standard IEEE-754 Half-Precision (FP16) instead of Single-Precision (FP32). Because the physical area of a hardware multiplier scales quadratically, an FP16 multiplier takes roughly **1/4th the area** of an FP32 multiplier. It also cuts the width of the register file, internal datapath, and multiplexers entirely in half, heavily reducing routing congestion on strict ASIC layouts.

## 3. No Hardware Divider (The Reciprocal LUT)

Floating-point division is massive and slow. Borg completely omits a hardware divider. Instead, it provides an `FRCP` (Fast Reciprocal) instruction.
It uses a tiny 17-entry BRAM lookup table (`rcpLut`) to get a highly accurate initial "guess" for `1/x`. If more precision is needed, the firmware does a Newton-Raphson iteration using the FMA pipeline. This completely eliminates the thousands of gates a real divider would require.

## 4. The 3-Copy Register File

A typical CPU/GPU register file needs 2 or 3 read ports to fetch operands simultaneously. However, FPGA Block RAMs (and many ASIC SRAM macros) usually only have **1 read port and 1 write port**. If you try to build a multi-port register file in standard Chisel, the synthesis tool will give up on BRAM and build it out of thousands of Flip-Flops (DFFs), ruining your area budget.
Borg solves this by instantiating **three identical copies** of the register file (`regFileA`, `regFileB`, `regFileC`). Whenever a register is written, it writes to all three simultaneously. When it reads `rs1`, `rs2`, and `rs3`, it just reads one from each copy. This maps perfectly to hard SRAM/BRAM blocks, saving massive amounts of standard logic.

## 5. The Coordinate Expansion LUT (No Barrel Shifters)

Converting integer screen coordinates to floating-point usually requires a Leading Zero Counter (Priority Encoder) and a Barrel Shifter to normalize the mantissa. Barrel shifters are notorious for consuming massive amounts of logic. Borg bypasses this entirely by using a 512-entry `coord_lut` ROM. On an FPGA, this trades an expensive algorithmic block for an otherwise unused memory block. On an ASIC, Yosys compresses this static lookup table into a surprisingly tiny logic cloud.

## 6. Firmware-Driven Setup (No Fixed-Function Geometry Engine)

Modern GPUs have massive fixed-function blocks just for setting up triangles (computing bounding boxes, edge equations, backface culling). Borg pushes all of this to software. The CPU firmware computes the triangle bounding box and edge slopes, and writes them to MMIO registers. The hardware *only* handles the dumb, fast inner loop of iterating over pixels and checking them against the edge equations.

## 7. Tile-Based Rendering (No Color/Depth Caches)

Standard GPUs write pixels to an external frame buffer, which requires massive, complex L1/L2 Color and Z-Depth caches to hide the memory latency. Borg renders small 4x4 (or 4x6) "tiles" exclusively into an internal, on-chip BRAM `TileBuffer`. It only writes to the slow external DRAM once the entire tile is completely finished. This completely eliminates the need for any cache controllers, cache tag RAMs, or eviction logic.

## 8. Tiny, Cacheless Instruction Memory (IMEM)

Borg has no instruction cache, branch predictor, or memory controller for shaders. It has a microscopic 31-instruction memory (IMEM) made of BRAM. It relies on the CPU (or DMA) to load a tiny "micro-kernel" shader program for the specific pass being rendered, run it, and then swap the program for the next pass.

## 9. Instruction-Level Uniform Overlays

Usually, reading from a "Uniform" buffer (constants like transformation matrices or light colors) requires complex instruction encoding, extra addressing modes, or a dedicated load/store unit. Borg implements a clever hack: it uses the `funct3` bits in the standard FMA instruction to selectively "overlay" the Uniform buffer onto `rs1`, `rs2`, or `rs3`. This allows the shader to do math directly against uniform variables without expanding the instruction decoder or adding new opcodes.

## 10. Barebones MMIO & Shared Bus (No AXI)

Commercial IP cores use complex, standardized buses like AXI4 or Avalon, which require massive state machines just to handle handshakes, burst transfers, and backpressure. Borg uses a hyper-minimalist, flat memory-mapped interface (`address`, `data_in`, `write_en`). Furthermore, it arbitrates access to the external DRAM (`GpuMemIO`) through a dead-simple multiplexer shared between the CPU, the Tile Flusher, and the Texture Fetcher, avoiding the need for a complex memory crossbar switch.

## 11. Zero-Overhead Memory Access (No Load/Store Unit)

A typical CPU or GPU core has a dedicated Load/Store Unit (LSU) to handle memory alignment, caching, and stalls. The `BorgCore` (the shader pipeline) has **no Load/Store instructions at all**. It only does math. All memory access (fetching textures, writing the final pixel to the tile buffer) is orchestrated by the `BorgRasterizer` state machine *outside* the core. The rasterizer fetches the texture, injects it into a register, triggers the math pipeline, and pulls the result out. This keeps the FPU strictly focused on math and completely free of memory stalling logic.

## 12. Snoop-Based Pipeline Interfacing

Instead of complex handshake protocols (like valid/ready signals) between the FPU and the rasterizer to know when a pixel is done, the rasterizer simply "snoops" the `pipeWrite` (write-back) bus of the FPU. When the final color is calculated and written to the destination register, the rasterizer intercepts it immediately on the wire and moves it to the Tile Buffer.

## 13. SystemRDL Generated Control Logic

Rather than hand-writing the Control and Status Register (CSR) decode logic in Chisel (which often leads to bloated multiplexers and area waste), Borg defines its registers in SystemRDL (`borg.rdl`). A compiler (`PeakRDL`) generates the mathematically optimal hardware logic for address decoding, read/write masks, and single-pulse clears directly into Chisel. It also generates the C headers, keeping hardware and software in perfect sync with zero manual logic bloat.

## 14. Burst-Optimized External Memory (No Complex DDR)

Instead of a massive, power-hungry DDR memory controller, Borg uses simple SPI/QSPI PSRAM. Because DRAM has high initial latency but is very fast for sequential reads/writes, Borg's `BorgTileFlusher` and `BorgDMA` are specifically built to *only* do linear burst transfers. This extracts maximum bandwidth from a cheap, low-pin-count memory chip without needing a complex, logic-heavy memory controller.

## 15. Hardcoded FSMs over Microcode

To sequence complex operations (like triangle traversal, texture fetching, and tile flushing), traditional architectures sometimes use microcode sequencers. Borg uses explicitly defined Chisel State Machines (FSMs) like `sIdle -> sTexFetch -> sShader -> sTileWrite`. On an FPGA and ASIC, hardcoded FSMs optimize down to just a few flip-flops and a tiny combinational logic cloud, whereas a microcode sequencer would require additional ROMs and program counters.
