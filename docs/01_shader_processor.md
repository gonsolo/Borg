# The Borg Shader Processor

The Borg is a small programmable shader unit, FP32 by default since September
2026 (FP16 remains selectable). The sequencer and the draw front end start it for
every vertex, quad of pixels and compute invocation; the CPU only sets up the
work through memory-mapped registers. The core was first built as a peripheral
the CPU fed by hand, which the MMIO interface described at the end still
allows.

This chapter walks through each piece of the processor — from how it stores
its state, through how it decodes and executes instructions, down to how
the CPU communicates with it over MMIO.

## Storage

At the heart of the processor are a small register file, an instruction memory,
and a program counter. The entire state fits in a handful of flip-flops.
Instruction memory, program counter, and the uniform RAM are **shared** —
`BorgCore` owns one copy each, since every lane executes the same fetched
instruction in lockstep:

{{snippet:hardware/borg/src/BorgCore.scala:storage}}

The **register file is per-lane**: `BorgCore` instantiates `cfg.fragLanes`
copies of `BorgLane` (1 lane in the scalar/vertex configuration, 4 lanes for
2×2-quad fragment SIMT), each with its own independent register file of 32
registers that the CPU can also read and write via MMIO. Programs live in DRAM
and are fetched through a direct-mapped instruction cache; a 72-word MMIO window
remains for programs loaded by hand. An all-zero instruction word halts the
program.

## Instruction Format

Every instruction is a 32-bit word in RISC-V's R-type or R4-type layout.
R-type operations use funct7 to select the operation; R4-type instructions
(FMA, TEX, TEXA) use rs3 as the third source operand. The `f3` field selects
which operand, if any, is read from the uniform window. The encodings are
defined in one file, described under
[Instruction Set Architecture](#instruction-set-architecture) below.

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

The control register at MMIO address 0x1A8 lets the CPU start execution (bit 0)
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

{{snippet:hardware/borg/src/BorgLane.scala:fma-muxing}}

The fifth operation, **FRCP**, provides hardware FP16 reciprocal (1/x) via a
33-entry LUT with linear interpolation.  It enables single-instruction
perspective division (W-divide) in the vertex shader.  FRCP shares its ROM
lookup and interpolation hardware with the FRSQ (reciprocal-sqrt) and FSRGB
(linear→sRGB) ops via one `Fp16Special` instance, muxed by which op is
latched active:

{{snippet:hardware/borg/src/BorgLane.scala:frcp}}

### Inside the RCP Unit

Computing 1/x in hardware without a divider is a classic GPU problem.  The
Borg solves it with a **piecewise-linear approximation**: split the FP16
mantissa into 32 intervals, store the exact reciprocal at each boundary in a
small lookup table, and linearly interpolate between entries using the
remaining mantissa bits.

The LUT stores 33 values (32 intervals + 1 sentinel), each 10 bits wide — a
total of 330 bits of ROM.  Doubling the original table fixes issue #2: the
worst `a * FRCP(b)` error drops from 3.295 to 2.245 ULP, below Vulkan's
2.5 ULP `OpFDiv` bound.

{{snippet:hardware/borg/src/BorgLutTables.scala:rcp-lut}}

The 5-bit reciprocal fraction is pre-doubled so rcp, rsq and sRGB can keep
sharing the existing interpolation datapath and fixed `>> 6` shift:

{{snippet:hardware/borg/src/Fp16Special.scala:rcp-interpolation}}

The exponent is simply inverted: `29 - exp` (since `1 / 2^e = 2^(-e)`, and
the FP16 bias is 15, so `(30 - 1) - exp = 29 - exp`).  The result achieves
~10-bit mantissa accuracy, matching FP16 precision without any Newton-Raphson
iteration.  `FrcpPrecisionTests` guards the 2.5 ULP bound against regressions.

## Instruction Set Architecture

Beyond the FMA family, the ISA has integer arithmetic, shifts and compares;
conversions between integer, FP32 and FP16; derivatives across the quad
(`DDX`, `DDY`); memory access (`LOAD`, `STORE`); texture sampling (`TEX`,
`TEXA`); the execution mask of the quad (`EXPUSH`, `EXELSE`, `EXPOP`,
`EXANY`); PC-relative branches (`BRZ`, `BRNZ` by a signed 10-bit word offset,
`JMP` by 18 bits); `BARRIER` for compute; and the draw front end's varyings
(`SOUT`, `FATTR`).

The ISA is defined in one file, `hardware/borg/isa/opcodes`, in the format of
the RISC-V riscv-opcodes project: a name, the operand fields from `fields.csv`,
and the fixed bits. Every bit of a word is either an operand or fixed, and the
generator rejects overlapping encodings:

```
fadd    rd rs1 rs2 f3       31..25=0x00 6..0=0x00
fmadd   rd rs1 rs2 rs3 f3   26..25=0 6..0=0x04
tex     rd rs1 rs2 rs3 f3   26..25=2 6..0=0x04
brnz    rs1 offset f3       31..25=0x28 6..0=0x00
```

`scripts/isa_gen.py` (`make isa`) generates the Scala decoder constants
(`Opcodes.scala`), the C header `borg_isa.h` and the compiler's `opcodes.rs`
from it, and `make isa-check` fails when a generated file is out of date. The
Scala assembler used by the tests builds on the generated constructors:

{{snippet:hardware/borg/src/Instructions.scala:isa-encoders}}

## Talking to the CPU

The CPU sees the shader processor as a set of memory addresses. Writing to an
address loads data; reading from one retrieves it. Rather than maintaining manual offsets, the address map is exclusively managed via **SystemRDL** (see `hardware/rdl/borg.rdl`) which automatically generates both the Chisel `BorgGpuRegs` layout and the C-headers mapping (`borg_regs.h`).

The address map is logically grouped into:

| Address Offset | Function |
| --- | --- |
| `0x000`–`0x07C` | Register file (r0–r31) |
| `0x080`–`0x19C` | Instruction memory (72 slots) |
| `0x1A8`–`0x1AC` | Status, Pipeline Control |
| `0x1B0`–`0x22C` | Uniform Memory (32 entries per page) |
| `0x230`–`0x248` | Tile Buffer, Command FIFO, Texture Config |

In normal operation the CPU does not drive the core instruction by instruction:
it writes a draw's state and one `SEQ_TRIGGER`, and the draw front end and
sequencer run the shaders (see [Geometry Front End](B1_geometry_front_end.md)).
The 2-entry **Command FIFO** decouples these register writes from the GPU's
clock.

{{snippet:hardware/borg/src/Borg.scala:mmio}}

The `data_ready` signal implements a simple handshake so the CPU knows when
read data is valid — important because the register file uses synchronous
reads with one cycle of latency.
