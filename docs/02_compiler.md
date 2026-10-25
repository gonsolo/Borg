# The Shader Compiler

borgc translates Vulkan shaders into Borg machine code. It is written in Rust
and lives inside Mesa, in `mesa/src/borg/compiler/`, next to the driver that
calls it. Mesa turns SPIR-V into NIR and runs the NIR passes Borg needs
(`borg_nir_passes.c`); borgc takes the NIR from there and returns a SPIR-B blob
the driver uploads.

The earlier Python compiler (`spirv_compiler.py`, `borg_backend.py`) is gone.

## The pipeline

One `ShaderState` carries a shader through five steps (`pipeline.rs`):

1. **Survey.** Count instructions and texture reads, pick the stage's preset
   registers, and decide whether push constants live in the uniform window.
2. **Selection** (`select.rs`). NIR instructions become Borg instructions on
   virtual registers. One selector serves vertex, fragment and compute shaders.
   Structured control flow maps onto the quad's execution mask: `if`/`else`
   become `EXPUSH`, `EXELSE` and `EXPOP`; loops branch back while any lane is
   still active (`EXANY` and a PC-relative `BRNZ`). An operation borgc cannot
   translate correctly makes it refuse the shader with a reason instead of
   emitting something close.
3. **Outputs.** Epilogues chosen by the driver's option word pack the
   fragment colour for the attachment format (raw formats, half, bytes, several
   targets) or expand points to quads.
4. **Optimisation.** Spilling down to a register limit when needed, placing
   constants (below), dead-code removal, FMA fusion and scheduling for register
   pressure.
5. **Assembly.** Fixed registers first (texture results in r20–r23, the
   per-vertex values `FATTR` writes, the outputs), then register allocation
   (`regalloc.rs`), encoding (`emit.rs`) and the blob.

If allocation fails, the driver's entry point recompiles with a tighter spill
limit.

## Registers and constants

A lane has 32 registers, and some are taken: the invocation IDs in r30/r31, the
texture result block, the stage's presets. Constants therefore do not live in
registers by default. Each instruction may read one operand from a small
uniform window, selected by its `f3` field. In a vertex or fragment shader borgc
places constants there; when there are more than fit, the window keeps the most
read ones and the rest are fetched from a pool in memory. In a compute shader the
most used constants become preset registers the driver fills, and the rest are
loaded from the pool near each use. An instruction that would need two window
operands gets one of them copied into a register first.

## The ISA, generated

borgc does not hand-encode instructions. The ISA is one file in riscv-opcodes
format, `hardware/borg/isa/opcodes`, and `scripts/isa_gen.py` generates
borgc's `opcodes.rs` from it at build time, from mesa's copy in `src/borg/isa`,
the same way it generates the hardware's decoder constants and the C header. Branches are PC-relative (`BRZ`/`BRNZ` by a signed
10-bit word offset, `JMP` by 18 bits), so a program runs wherever it is loaded;
a branch out of `BRNZ`'s reach becomes a `BRZ` over a `JMP`.

## Compute

Compute shaders take the same path. `barrier()` lowers to `BARRIER`, values
live across it are saved, and integer atomics on storage buffers and shared
memory are supported. A workgroup of one invocation would leave three of the
four quad lanes idle, so borgc packs four such workgroups into one quad. The
CTS precision tests all dispatch this way; together with smaller dispatch slices
in the driver, an `atan2` vec4 case went from over 40 minutes to about two.

## How changes are checked

- **Rust unit tests**, built with `-Dbuild-tests=true` (`meson test --suite borg`).
- **Replay:** with `BORGC_REPLAY_DIR` set, the driver saves every shader's NIR;
  `borgc-replay` recompiles them in seconds, so a refactoring can be proven
  byte-identical.
- **Corpus:** `scripts/borgc_corpus/check.sh` reruns the CTS cases whose
  shaders reach borgc (graphics, and with `SET=compute` the compute set) and
  compares pass/fail and the compiled blobs with a reference.

## Further reading

- [Compiler and Driver Contract](B0_compiler_contract.md): what the hardware
  expects from compiled code.
- [ISA Gaps](B4_isa_gaps.md): what shaders need that the ISA lacks.
- [borgc design notes](B6_borgc_notes.md): the reasons behind individual
  decisions in the source.
