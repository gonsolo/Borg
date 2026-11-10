# Vulkanised 2027: video and poster

Accepted 2026-11-10 as a Video Vault talk (pre-recorded, 20-25 min) and a poster in Kortrijk.
Deadlines, video format and poster size: asked, not known yet. Until then: A0 portrait, 1080p.

Both tell the same story as `abstract.tex`, updated to today. Wording: no "conformant" or
"compatible"; numbers only from runs that can be reproduced.

## Changed since the abstract (10-20)

- The serial path and the firmware are gone (11-04/05). Back ends now: the direct simulator, and
  the DRM driver (Linux on the board, or QEMU).
- ISA v2: the core moves to a RISC-V subset (RV32I, Zfinx, Zmmul, Zbb, Zicond) plus GPU
  instructions. Constants are built in code (`ADDI`/`LUI`); the uniform window is going away.
- Device memory: one heap, render targets and textures in place (B13).
- Second board: Nexys Video (Artix-7, DDR3), port in progress.
- CTS: full run 10-29 (old state): 177,519 Pass, 4,279 Fail, 2,611 Timeout. Needs a fresh run
  before the numbers go on a slide.
- Compiler: per-draw fragment work hoisted into the vertex shader; slow cases from 5.5 h to 2 h.

## Video (about 22 min)

| # | Part | Min | On screen |
|---|------|-----|-----------|
| 0 | vkcube on the board, no words | 0.5 | footage: board, HDMI display |
| 1 | What Borg is | 2 | stack diagram, the three targets (ULX3S, Nexys Video, GF180) |
| 2 | What Vulkan 1.0 asks of hardware | 6 | the requirement/mechanism table, one slide per row that matters |
| 3 | A driver and compiler for 32 registers | 5 | NIR to Borg ISA, the opcode file, refusal, corpus, the hoist |
| 4 | Running the CTS against the RTL | 6 | direct simulator, parallel runs, a live terminal run, bugs found |
| 5 | Lessons and what is next | 2 | the lessons list, must-pass list, tapeout |
| 6 | Links | 0.5 | repo, book, QR codes |

### 2. What Vulkan 1.0 asks of hardware
- A graphics queue implies compute: workgroups on the same core; the precision tests run as
  compute, so this path gates thousands of shader cases.
- Derivatives need quads: 2x2 on four lanes; implicit LOD uses the same quad.
- Descriptors become hardware tables: 51 formats, mipmaps, all address modes; render targets in
  the same layout, so render to texture needs no copy.
- The draw front end: one register write per draw, homogeneous setup without clipping.
- Formats beyond RGBA8 as raw attachments, blending by read-back; 4x MSAA in passes per tile.

### 3. A driver and compiler for 32 registers
- Mesa runtime and WSI; borgc consumes NIR after the standard lowering; one selector for
  graphics and compute.
- One opcode file generates the decoder, the header and the encoder.
- Refuse instead of guess; a corpus of recorded shaders checks every change.
- Example: what the fragment shader computes from push constants alone runs once per triangle in
  the vertex shader (`nir_opt_preamble`): 320 to 133 instructions per pixel.

### 4. Running the CTS against the RTL
- Direct simulator: the RTL compiled with arcilator, about 120k cycles/s, many instances.
- Throughput decides: quad packing for compute (40 min to 2 min), strips for large passes.
- Bugs found before silicon: textureGather corners, integer bits through a float move, branch
  targets one word early, a constant placed after its use (11-10).
- Numbers: a fresh full run (table and a bar chart by group).

## Poster (A0 portrait)

Top: title, name, the board photo. Then three columns:
1. Stack diagram; the three targets.
2. Vulkan requirement to hardware table; the compiler (opcode file excerpt, the hoist).
3. CTS against RTL: the loop from deqp-vk to the simulator, the results chart, bugs found.
Bottom: lessons, QR codes to the repository, the book and the video.

## Production

- Slides and poster in LaTeX next to `abstract.tex` (beamer, tikzposter), one `make`.
- Recording: OBS, slides plus voice; footage of the board recorded with a camera.
- Live parts: a terminal running `cts_par.sh` on a few cases; vkcube on the simulator.

## Open

- Rust in the talk? The abstract left it out on purpose.
- Camera on the speaker, or voice only?
- A fresh full CTS run for the numbers (CI, needs a go).
- New footage of the board (and the Nexys Video, if it runs by then).
