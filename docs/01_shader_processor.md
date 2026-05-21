# The Borg Shader Processor

The Borg is a minimal programmable shader unit designed for FP16 graphics
operations. It operates as a memory-mapped peripheral within the Hutt RISC-V
SoC: the CPU writes a small program into the shader's instruction memory,
fills its registers with input data, and then tells it to run. A few clock
cycles later the results appear in the register file, ready to be read back.

This chapter walks through each piece of the processor — from how it stores
its state, through how it decodes and executes instructions, down to how
the CPU communicates with it over MMIO.

## Storage

At the heart of the processor are a small register file, an instruction memory,
and a program counter. The entire state fits in a handful of flip-flops:

{{snippet:hardware/borg/src/BorgCore.scala:storage}}

The register file holds 32 FP16 values that the CPU can read and write via MMIO.
The instruction memory stores up to 32 shader instructions — enough for full
4×4 MVP matrix multiplies and complex fragment shading.  An all-zero instruction
word acts as a halt sentinel, so the longest useful program is 31 instructions
plus the implicit stop.

## Instruction Format

Every instruction is a 32-bit word using standard RISC-V encoding.
R-type operations (ADD, MUL, FNEG, FSTEP, FRCP) use funct7 to select the
operation.  R4-type FMA uses rs3 as the third source operand.

See `Instructions.scala` for the full encoding definition.

Here is how the decoder extracts these fields from the fetched instruction:

{{snippet:hardware/borg/src/BorgCore.scala:instruction-format}}

## Fetch and Execute

The processor runs a simple loop: fetch an instruction, execute it over 4
clock cycles, then advance the program counter. A `busy_counter` register
tracks where we are in the pipeline:

1. **Idle** — `running` is false. The CPU can load registers and instructions.
2. **Fetch** — `running` goes true. The instruction at `programCounter` is
   read from memory (1-cycle SyncReadMem latency).
3. **Execute** — `busy_counter` counts down from 4. The FMA unit computes
   the result across these cycles.
4. **Write-back** — when `busy_counter` hits 1 the result is written to `rd`
   and the program counter advances.

If the fetched instruction is zero, the processor halts instead of executing.

{{snippet:hardware/borg/src/BorgCore.scala:fetch-execute}}

The control register at MMIO address 60 lets the CPU start execution (bit 0)
or reset the processor (bit 1). A reset clears the program counter and stops
execution immediately.

## The ALU

A key insight of the Borg's design is that **every arithmetic operation is
secretly an FMA** (fused multiply-add). The hardware contains a single FMA
unit (computing *a × b + c*), and the different operations are implemented by
choosing what goes into its three inputs:

| Operation | a | b | c | Result |
| --- | --- | --- | --- | --- |
| ADD | 1.0 | rs1 | rs2 | rs1 + rs2 |
| MUL | rs1 | rs2 | 0.0 | rs1 × rs2 |
| FMA | rs1 | rs2 | rs3 | rs1 × rs2 + rs3 |
| FNEG | 1.0 | rs1 | 0.0 | −rs1 (via op flag) |

This "one unit, many operations" trick saves a huge amount of silicon — instead
of separate adder, multiplier, and negation circuits, we reuse one FMA datapath
for everything:

{{snippet:hardware/borg/src/BorgCore.scala:fma-muxing}}

The fifth operation, **FSTEP**, doesn't use the FMA at all.  It implements a
step function used during rasterization to test whether a pixel is inside a
triangle edge.  The result is simply 1.0 for positive inputs and 0.0 otherwise:

{{snippet:hardware/borg/src/BorgCore.scala:fstep}}

The sixth operation, **FRCP**, provides hardware FP16 reciprocal (1/x) via a
17-entry LUT with linear interpolation.  It enables single-instruction
perspective division (W-divide) in the vertex shader:

{{snippet:hardware/borg/src/BorgCore.scala:frcp}}

### Inside the RCP Unit

Computing 1/x in hardware without a divider is a classic GPU problem.  The
Borg solves it with a **piecewise-linear approximation**: split the FP16
mantissa into 16 intervals, store the exact reciprocal at each boundary in a
small lookup table, and linearly interpolate between entries using the
remaining mantissa bits.

The LUT stores 17 values (16 intervals + 1 sentinel), each 10 bits wide — a
total of just 170 bits of ROM:

{{snippet:hardware/borg/src/BorgCore.scala:rcp-lut}}

The interpolation uses only a 7×6-bit multiply and a subtraction —
no full multiplier is needed:

{{snippet:hardware/borg/src/Fp16Rcp.scala:rcp-interpolation}}

The exponent is simply inverted: `29 - exp` (since `1 / 2^e = 2^(-e)`, and
the FP16 bias is 15, so `(30 - 1) - exp = 29 - exp`).  The result achieves
~10-bit mantissa accuracy, matching FP16 precision without any Newton-Raphson
iteration.

## Instruction Set Architecture

The ISA is defined in a single Scala object (`Instructions.scala`) that serves
as the **single source of truth** for both hardware decoding and software
encoding.  The same bit-field definitions are used by the Chisel decoder, the
C header generator, and the Python SPIR-V compiler backend:

{{snippet:hardware/borg/src/Instructions.scala:isa-bitfields}}

The instruction constructors let Scala code build valid encodings by name,
which `MmioMap.scala` then emits as C macros and Python functions:

{{snippet:hardware/borg/src/Instructions.scala:isa-encoders}}

## Talking to the CPU

The CPU sees the shader processor as a set of memory addresses. Writing to an
address loads data; reading from one retrieves it. Rather than maintaining manual offsets, the address map is exclusively managed via **SystemRDL** (see `borg_gpu.rdl`) which automatically generates both the Chisel `BorgGpuRegs` layout and the C-headers mapping (`borg_regs.h`).

The address map is logically grouped into:

| Address Offset | Function |
| --- | --- |
| `0x000`–`0x07C` | Register file (r0–r31) |
| `0x080`–`0x15C` | Instruction memory (56 slots) |
| `0x164`–`0x16C` | Status, Pipeline Control |
| `0x170`–`0x1EC` | Uniform Memory (32 entries per page) |
| `0x1F0`–`0x214` | Tile Buffer, Command FIFO, Texture Config |

A typical workflow looks like this: the CPU writes a shader program into the
instruction memory, fills the input uniforms, and instead of blocking, queues asynchronous rendering descriptors to the 2-entry **Command FIFO**. The FIFO then handles passing the commands (like rasterization iterator values and shader PC triggers) to the GPU hardware logic while the CPU computes the next triangle.

{{snippet:hardware/borg/src/Borg.scala:mmio}}

The `data_ready` signal implements a simple handshake so the CPU knows when
read data is valid — important because the register file uses synchronous
reads with one cycle of latency.
