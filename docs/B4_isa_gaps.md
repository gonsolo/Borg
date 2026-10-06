# ISA Gaps: What Shaders Need and the Borg ISA Lacks

Status: written 2026-10-06 from `hardware/borg/src/Instructions.scala`, `BorgLane.scala` and
`mesa/src/borg/compiler/lib.rs`. It is a reading of those files, not a hardware measurement; the
claims marked *(to verify)* have not been checked against the RTL.

The ISA grew one demo at a time. Its special-function set is `FRCP`, `FRSQ` and `FSRGB`; `FSTEP`
is the only comparison. Everything a shader does beyond that, `borgc` builds from these pieces or
does not build at all. The last part is the danger: an unhandled operation is skipped without an
error, and the shader renders wrong. That is how the cube lost its texture on 2026-10-06 (see
"A case: the sRGB idiom").

## What exists

| Group | Instructions |
|---|---|
| Float | `ADD`, `MUL` (and the FMA path), `FNEG`, `FSTEP`, `FRCP`, `FRSQ`, `FSRGB`, `FMOV`, `I2F`, `F2I` |
| Integer | `IADD`, `ISUB`, `IMUL`, `ISHL`, `ISHR`, `ISRL`, `IAND`, `IOR`, `IXOR`, `ISLT`, `ISLTU`, `ISEQ` |
| Quad | `DDX`, `DDY` (fixed: lane1 − lane0, lane2 − lane0) |
| Memory | `LOAD`, `STORE` (word index from `LS_BASE`), `TEX`/`TEXA`, `TLD` |
| Control | `BRZ`, `BRNZ`, `JMP`, `EXPUSH`/`EXELSE`/`EXPOP` (predication), `EXANY`, `BARRIER` |
| Fixed function | `SOUT`, `FATTR`, `ZTEST`, `SMASK`, `ATTIDX` |

`FSTEP(x)` is 1.0 when x > 0 and 0.0 when x is negative or zero (`computeFstep` in `BorgLane.scala`).

## Gaps, most used first

| Missing | What a shader needs it for | What `borgc` does today |
|---|---|---|
| `pow`, `log2`, `exp2` | `pow`, `exp`, `log`, gamma, lighting models | Nothing, except the sRGB idiom below. Any other `fpow` is skipped silently. |
| Float compare, min/max, select | `<`, `max`, `min`, `clamp`, `mix`, `? :` | `FSTEP` + `FMUL` for `max`; select as `else + cond·(then − else)`; comparisons must yield exactly 0.0/1.0 |
| `floor`, `fract`, `ceil`, `round`, `abs`, `sign` | tiling, wrap modes, noise | not compiled *(to verify per op)*; `F2I` truncates |
| `sqrt` | normalise, length | `x·rsq(x)` is wrong at 0 *(to verify what is emitted)* |
| `sin`, `cos`, `tan` and inverses | animation, procedural shading | not compiled |
| Immediates, zero register | every constant | constants live in DMA'd window words (`u20–u31` fragment, `u25–u31` vertex); a zero is built as `FSTEP(FNEG(r30))` for the texture control word |
| `FSUB`, `FDIV` | arithmetic | `FNEG` + `ADD`; `FRCP` + `MUL` |

## Semantics that differ from float code

- `BRZ`, `BRNZ` and `EXPUSH` test the raw register bits against zero, so −0.0 counts as non-zero.
- `DDX`/`DDY` are fixed to the 2×2 quad's lane layout.
- `FSRGB` and the other table functions interpolate between ROM entries, so they are not
  correctly rounded.

## A fixed register ABI inside the compiler

The fragment stage reserves registers: edges `r0–2`, TEX results `r20–23`, alpha `r24`, kill
`r25`, outputs `r26–29`, pixel centre `r30`/`r31`. Constants may use only what is left (`r17–19`,
and `r23` in a shader that never samples), or the window words. A miscounted slot clobbers a
value silently; `lib.rs` records one case where handing out `r20` put a `fge` result where the
texel lands.

## Stale documentation

- `Instructions.scala` still describes the integer ops as "16-bit, operate on raw bits" and
  `I2F`/`F2I` as "signed int16", but the datapath has been FP32 since 2026-09-15 and `ISRL` shifts
  by `rs2 & 31` *(to verify what each integer op does now)*.
- It still says the instruction memory has "56–72 words"; programs are up to 1024 words through
  the I-cache.
- FTEX (`0x0C`) is retired, not reused; that is deliberate.

## A case: the sRGB idiom

`FSRGB` (added 2026-06-10, Phase D M2) is the only hardware that can compute
`linearToSrgb`. `borgc` finds `bcsel(fge(knee, x), x·12.92, 1.055·pow(x, 1/2.4) − 0.055)` by
looking for the NIR constant 12.92 in the linear branch. Commit `5569882b1fb` gave scalar float
constants a window register when an arithmetic instruction reads them. The idiom check still
looked for the NIR constant, found a window register, and failed. The compiler then emitted the
generic select around an unhandled `fpow`, and cube.frag rendered a lit gradient with no texture.
The recorded golden stream predates that commit, so no test noticed. The fix (`6a0d8a437f0`)
makes the check accept a window word; the idiom is still fragile.

## Order of work

1. Make `borgc` fail with an error on any NIR ALU op it cannot compile, instead of skipping it.
2. Lower `pow`, `log2`, `exp2` in `borgc` from existing instructions (exponent bits through
   `ISHR`/`IADD`/`I2F`, mantissa polynomial in `FMADD`, `F2I` and `ISHL` for 2^k). No hardware change.
3. Lower `floor`, `fract`, `ceil`, `abs`, `sign`, `sqrt`, `min`, `max`, `clamp` the same way.
4. Measure fragment cycles on the cube with `FSRGB` replaced by the software form. If the cost
   is acceptable, remove `FSRGB` from the hardware (decode, flag, ROM, mux, tests, ISA header);
   the texture unit's `srgbRom` is a different table and stays.
5. If a hardware unit is worth its area, add `FLOG2`/`FEXP2` next to `FRSQ`.
6. Add a compiler test that renders cube.frag from its GLSL on every run, so a change in
   `borgc` cannot pass the golden by replaying an old stream.
