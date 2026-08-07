// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** BorgRasterRom — the edge-function rasterizer shader, baked into a permanent
  * hardware ROM instead of living in the writable, per-draw-shader
  * `instructionMemory`.
  *
  * The rasterizer program (source: software/borg/compiler/rasterize.s) is
  * identical for every triangle on every target -- it is never recompiled or
  * reuploaded at runtime (see software/borg/compiler/Makefile: "rasterize.s
  * is still hand-written Borg ISA... hand-written, fixed-function"). Baking
  * it into BorgCore as a ROM means it no longer competes with the fragment
  * shader for the shared IMEM budget (previously: rast 13 + frag N had to be
  * simultaneously resident, capping ASIC's usable frag budget at
  * maxInstructions-13).
  *
  * The 12 real instruction words below are byte-for-byte the same words the
  * SPIR-B loader (`spirb_parse`, software/borg/borg_spirb.c) would extract
  * from the checked-in `rasterize_borg` blob in
  * software/borg/compiler/shader_blobs.h -- parsed with that exact format
  * (6-byte header: num_instrs=12, num_uniforms=12, num_attributes=0,
  * num_outputs=3, num_consts=0, reserved; then 12 little-endian u32 words).
  * A trailing 0 (HALT) is appended as the 13th ROM entry, matching the
  * `borg_load_spirb_shader_at` upload convention that always HALT-terminates
  * non-borgc-compiled blobs.
  *
  * Consumes uniform slots 0-11 (dx0, neg_dy0, dx1, neg_dy1, dx2, neg_dy2,
  * neg_vx0, neg_vy0, neg_vx1, neg_vy1, neg_vx2, neg_vy2 -- see rasterize.s's
  * `@borg uniform` directives) and writes e0/e1/e2 to r0/r1/r2. Uniform
  * staging (borg_load_edge_constants in software/borg/borg_driver.c) is
  * completely unchanged by this ROM -- only instruction *fetch* moves.
  */
private[borg] object BorgRasterRom {
  val instructions: Seq[BigInt] = Seq(
    BigInt("006f2180", 16),
    BigInt("007fa200", 16),
    BigInt("08401000", 16),
    BigInt("00309004", 16),
    BigInt("008f2180", 16),
    BigInt("009fa200", 16),
    BigInt("08411080", 16),
    BigInt("08319084", 16),
    BigInt("00af2180", 16),
    BigInt("00bfa200", 16),
    BigInt("08421100", 16),
    BigInt("10329104", 16),
    BigInt("00000000", 16) // HALT sentinel
  )
}
