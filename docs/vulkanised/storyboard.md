# Vulkanised 2027: video and poster

Accepted 2026-11-10 as a Video Vault talk (pre-recorded, 20-25 min) and a poster in Kortrijk.
Deadlines, video format and poster size: asked, not known yet. Until then: A0 portrait, 1080p.

Both tell the same story as `abstract.tex`, updated to today. Wording: no "conformant" or
"compatible"; numbers only from runs that can be reproduced.

## Changed since the abstract (10-20)

- The serial path and the firmware are gone (11-04/05).
- ISA v2 is in: RISC-V encodings (RV32I, Zfinx, Zmmul, Zicond, some Zbb), GPU instructions in the
  custom opcode space, all from one opcode file.
- The Nexys Video (Artix-7, DDR3) is the main board: Hutt (RV64) boots Linux, the Rust DRM/KMS
  driver shows vkcube full screen on HDMI (11-17). The ULX3S is retired; the ASIC flow
  (LibreLane, GF180) stays. An early Borg (FP16, TinyQV) is on a Tiny Tapeout IHP chip, due 02/2027.
- The board is the conformance target (11-16): borgvk drives Borg over USB (`borg_ftdi`, the
  HostLink protocol, about 15 MB/s), Borg at 25 MHz against the simulator's ~120k cycles/s. Board
  and simulator agree byte for byte on the driver corpus (825 cases, 2,631 image hashes). Every
  known timeout passes on the board; the simulator's speed-ups (strips, pass cache, CTS packs)
  are gone (11-17).
- Device memory: one heap at the same GPU address on every target. Simulator: shared memory.
  USB: the host keeps a copy, uploads changed pages, the board reports the pages Borg wrote
  (a dirty bitmap in HostLink). Linux: GEM objects in Borg's window at their GPU address,
  coherent through Hutt's write-through cache, no copies (11-17).
- Compiler: per-draw fragment work hoisted into the vertex shader.
- CTS numbers: the full run 10-29 is old; a clean full run on the board from one commit comes
  next. Only its numbers go on a slide.

## Video (about 22 min)

| # | Part | Min | On screen |
|---|------|-----|-----------|
| 0 | vkcube on the board, no words | 0.5 | footage: Nexys Video, vkcube full screen on HDMI |
| 1 | What Borg is | 2 | the stack, the back ends, the targets |
| 2 | What Vulkan 1.0 asks of hardware | 5 | the requirement/mechanism table, one slide per row that matters |
| 3 | A driver and compiler for 32 registers | 5.5 | borgvk, the opcode file, the hoist, one heap everywhere |
| 4 | Running the CTS against the hardware | 6.5 | the simulator, the board over USB, byte-identical, a live run, bugs |
| 5 | Lessons and what is next | 2 | the lessons list, the full run, Linux, tapeout |
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
- One opcode file in riscv-opcodes format generates the decoder, the header and the encoder.
- Refuse instead of guess; a corpus of recorded shaders checks every change.
- Example: what the fragment shader computes from push constants alone runs once per triangle in
  the vertex shader (`nir_opt_preamble`): 320 to 133 instructions per pixel.
- One device heap, every target: same GPU addresses in the simulator, over USB and under Linux.

### 4. Running the CTS against the hardware
- Simulator first: the RTL compiled with arcilator, about 120k cycles/s; where bring-up and
  debugging happen.
- Then the board: the same command stream over USB, Borg at 25 MHz; checked against the
  simulator byte for byte; the dirty-page bitmap keeps the link cheap.
- Throughput decides: quad packing for compute (40 min to 2 min); the slowest known cases
  (explicit LOD 128x128, 1 MiB buffers) pass on the board in minutes to an hour.
- Bugs found before silicon: textureGather corners, integer bits through a float move, branch
  targets one word early, a constant placed after its use, a read that gave up too early.
- Numbers: the clean full run on the board (table and a bar chart by group).

## Poster (A0 portrait)

Top: title, name, the board photo. Then three columns:
1. Stack diagram; the back ends; the board running Linux.
2. Vulkan requirement to hardware table; the compiler (opcode file excerpt, the hoist).
3. CTS against the hardware: simulator and board, byte-identical; the results chart, bugs found.
Bottom: lessons, QR codes to the repository, the book and the video.

## Production

- Slides and poster in LaTeX next to `abstract.tex` (beamer, tikzposter), one `make`.
- Recording: OBS, slides plus voice; footage of the board recorded with a camera. The voice is
  the speaker's own, recorded per section with retakes, then `clean_voice.sh take.wav out.wav`
  (rumble and noise out, de-essing, compression, -16 LUFS, 48 kHz).
- Live parts: a terminal running `cts_par.sh` on the board (quick list: 114 cases in 16 s).

## Open

- The clean full CTS run on the board for the numbers (needs a go; after the unified memory work).
- Footage: the Nexys Video with vkcube full screen on HDMI, filmed with a camera.

Decided 11-10: Rust is in the talk (the driver's language); voice only, no camera.
