# TinyQV TODO

- [ ] Remove custom TinyQV instructions from hardware decoder (`c.lwtp`, `c.swtp`, `c.mul16`, `lw2`, `lw4`, `sw2`, `sw4`, `sw4n`, `mul16`). These repurpose standard RISC-V encoding slots (`c.flwsp`, `c.fswsp`, `c.fsdsp`) which conflicts with standard toolchains. Software side is already done — Decode.scala and SDK macros updated.

- [ ] Investigate why GCC 15.2 with frame pointer (`-fno-omit-frame-pointer`, the default on RV32E) generates code that hangs TinyQV. The `s0`-relative addressing pattern from `addi s0, sp, N` + `sw/lw reg, offset(s0)` causes the rasterization loop to hang. Currently worked around with `-fomit-frame-pointer`. This likely indicates a hardware conformance issue in TinyQV's pipeline — possibly related to `s0`/`x8` register handling or specific instruction sequences involving frame pointer setup.
