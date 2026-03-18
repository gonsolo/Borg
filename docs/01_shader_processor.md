# The Borg Shader Processor

The Borg is a minimal programmable shader unit designed for FP16 graphics
operations. It operates as a memory-mapped peripheral within the TinyQV RISC-V
SoC: the CPU writes a small program into the shader's instruction memory,
fills its registers with input data, and then tells it to run. A few clock
cycles later the results appear in the register file, ready to be read back.

This chapter walks through each piece of the processor — from how it stores
its state, through how it decodes and executes instructions, down to how
the CPU communicates with it over MMIO.

## Storage

At the heart of the processor are a small register file, an instruction memory,
and a program counter. The entire state fits in a handful of flip-flops:

{{snippet:borg/src/Borg.scala:storage}}

The register file holds 8 FP16 values that the CPU can read and write via MMIO.
The instruction memory stores up to 6 shader instructions — enough for simple
vertex transformations and fragment shading operations. An all-zero instruction
word acts as a halt sentinel, so the longest useful program is 5 instructions
plus the implicit stop.

## Instruction Format

Every instruction is a single 16-bit word. The top two bits select the
operation, and the remaining bits name the source and destination registers:

| Bits | Field | Meaning |
| --- | --- | --- |
| 15–14 | op | 00 = ADD, 01 = MUL, 10 = FMA, 11 = extended |
| 13–12 | rs3 / ext | Third source (FMA) or sub-opcode (extended) |
| 11–8 | rs2 | Second source register |
| 7–4 | rs1 | First source register |
| 3–0 | rd | Destination register |

The "extended" encoding (op = 11) uses the rs3 field to select additional
operations: 00 = FNEG (negate), 01 = FSTEP (step function). This gives us
five operations total — just enough for the vertex and fragment math a tiny
GPU needs.

Here is how the decoder extracts these fields from the fetched instruction:

{{snippet:borg/src/Borg.scala:instruction-format}}

Notice how the code supports both FP16 and FP32 instruction widths — the
`if (config.totalBits >= ...)` guards select wider bit-fields for 32-bit
mode while keeping the logic identical in structure.

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

{{snippet:borg/src/Borg.scala:fetch-execute}}

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

{{snippet:borg/src/Borg.scala:fma-muxing}}

The fifth operation, **FSTEP**, doesn't use the FMA at all. It implements a
step function used during rasterization to test whether a pixel is inside a
triangle edge. The result is simply 1.0 for positive inputs and 0.0 otherwise:

{{snippet:borg/src/Borg.scala:fstep}}

## Talking to the CPU

The CPU sees the shader processor as a set of memory addresses. Writing to an
address loads data; reading from one retrieves it. The address map is divided
into three regions:

| Address range | Function |
| --- | --- |
| 0–28 (words 0–7) | Register file (r0–r7) |
| 32–52 (words 8–13) | Instruction memory (6 slots) |
| 60 | Control / status register |

A typical workflow looks like this: the CPU writes a shader program into the
instruction memory, fills the input registers, writes a 1 to the control
register to start execution, and then polls the status register until the
"done" bit is set. It can then read the output registers.

{{snippet:borg/src/Borg.scala:mmio}}

The `data_ready` signal implements a simple handshake so the CPU knows when
read data is valid — important because the register file uses synchronous
reads with one cycle of latency.
