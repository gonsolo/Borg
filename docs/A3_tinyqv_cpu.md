# The TinyQV CPU

TinyQV is a nibble-serial RV32I RISC-V processor originally designed by Michael Bell
for Tiny Tapeout. The original Verilog was rewritten in Chisel and heavily modified
to integrate with the Borg shader processor.

## Module Hierarchy

Before diving into the details, here is how the pieces fit together:

```
TinyQV  (top-level SoC wrapper)
├── TinyQVCpu         — Pipeline: decode → execute, stalls, branches
│   ├── TinyQVDecode  — Combinational instruction decoder (RV32IC)
│   ├── TinyQVCore    — Execution engine (4-bit ALU, shifter, register file)
│   │   └── CsrFile   — CSR registers, interrupts, cycle/time counters
│   └── TinyQVTime    — mtime/mtimecmp timer comparator
└── TinyQVMemCtrl     — QSPI bus arbitrator (flash for instructions, PSRAM for data)
    └── QspiController — Low-level QSPI state machine
```

`TinyQV` is the entry point. It instantiates the CPU and the memory controller,
then routes memory transactions based on address decoding: addresses with
bits [27:25] == 0 go to the QSPI memory controller (flash/PSRAM), while all
other addresses go to external MMIO peripherals (like the Borg GPU).

## Nibble-Serial Architecture

The key insight of TinyQV is processing 32-bit values **4 bits at a time**.
Instead of a 32-bit ALU, it uses a 4-bit ALU that iterates 8 times per instruction.
This dramatically reduces area at the cost of throughput — each instruction takes
8 clock cycles instead of 1.

### The 4-Bit ALU

The ALU operates on nibbles with carry propagation between cycles:

{{snippet:tinyqv/src/cpu/Alu.scala:alu}}

Note the 4-bit inputs (`a`, `b`) and the carry chain (`cy_in`, `cy_out`). The CPU
feeds nibbles from the register file one at a time, and the carry propagates across
cycles to produce a full 32-bit result.

### Nibble Counters

The 32-bit program counter and address registers are implemented as shift registers
of 8 nibbles. Each cycle processes one nibble position:

{{snippet:tinyqv/src/cpu/Counter.scala:nibble-counter}}

The shift register rotates through all 8 positions, applying the increment carry
chain as it goes. After 8 cycles, the full 32-bit value has been updated.

## The Two-Stage Pipeline

TinyQV has a simple two-stage pipeline:

1. **Decode** — happens combinationally during `counter_hi == 7` of the previous
   instruction's execution. The decoder reads the raw instruction bits and latches
   decoded fields (instruction type, ALU operation, register indices, immediate).

2. **Execute** — runs for 8 nibble cycles (`counter_hi` 0–7). The core processes
   one nibble per clock: ALU, loads, stores, branches. CSR and interrupt logic
   runs in parallel.

A 3-bit counter (`counter_hi`) drives both stages. When it reaches 7, the current
instruction completes and the next one begins decoding — achieving one-instruction
overlap between decode and execute.

### Stalls and Branches

The pipeline stalls when:
- **No instruction ready**: the fetch buffer hasn't received enough instruction
  data from flash yet.
- **Load/store pending**: a previous memory access hasn't completed.

Branches work as follows:
- **JAL** can redirect fetch *early* (one beat before decode completes), since
  the target address is fully available from the immediate.
- **Conditional branches** redirect at the end of the execute stage after
  evaluating the condition through the nibble ALU's comparator chain.
- **Interrupts** drain the pipeline cleanly: the current instruction completes,
  then the interrupt handler address is fetched.

## Instruction Decoder

The decoder is purely combinational — no clock, no state, just wires. It handles
both 32-bit RV32I instructions and 16-bit compressed (RV32C) instructions
without expanding compressed instructions into their 32-bit equivalents.

Instead, both formats decode directly to the same output signals:
an instruction type enum, ALU opcode, memory operation width, register indices
(4-bit for RV32E's 16 registers), and sign-extended immediates.

The decoder distinguishes 32-bit from 16-bit instructions by checking `instr[1:0]`:
if both bits are 1, it's a 32-bit instruction; otherwise it's compressed.

## Core Datapath

The core module connects the register file and ALU. On each cycle, a nibble
of the source registers is fed to the ALU, and the result nibble is written back:

{{snippet:tinyqv/src/cpu/Core.scala:core-datapath}}

The `counter` signal (0–7) selects which nibble is being processed. When `counter`
reaches 7, the instruction is complete and the next one begins decoding.

## Memory Controller

The memory controller manages a single QSPI bus shared between two competing
consumers:

- **Instruction fetch** — streams 16-bit instruction words from SPI flash.
  This runs continuously in the background, filling a 4-entry instruction buffer.
- **Data load/store** — reads/writes bytes, halfwords, or words from PSRAM.
  Data transactions take priority over instruction fetch.

When a load or store arrives, the controller stops the instruction stream,
services the data request, then restarts fetching. Multi-beat (continued)
transactions are supported for sequences like load/store-multiple.

The QSPI interface reassembles byte-wide SPI data into the CPU's 32-bit data
bus. All of this happens transparently — the CPU sees a simple ready/valid
handshake and never deals with SPI protocol details.
