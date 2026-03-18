# The TinyQV CPU

TinyQV is a nibble-serial RV32I RISC-V processor originally designed by Michael Bell
for Tiny Tapeout. The original Verilog was rewritten in Chisel and heavily modified
to integrate with the Borg shader processor.

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

## Core Datapath

The core module connects the register file and ALU. On each cycle, a nibble
of the source registers is fed to the ALU, and the result nibble is written back:

{{snippet:tinyqv/src/cpu/Core.scala:core-datapath}}

The `counter` signal (0–7) selects which nibble is being processed. When `counter`
reaches 7, the instruction is complete and the next one begins decoding.
