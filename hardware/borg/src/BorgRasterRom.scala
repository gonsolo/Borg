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
  *
  * REGISTER CLOBBER ABI -- running this ROM destroys r0..r4 on EVERY pixel:
  *   r0, r1, r2  = the e0/e1/e2 edge outputs (read by the inside-flag snoop)
  *   r3, r4      = the per-edge dpx/dpy scratch pair, reused three times
  *                 (r3 = r30 + neg_vx_k, r4 = r31 + neg_vy_k)
  * Because BORG_ITER auto-run always runs this ROM before chaining into the
  * fragment shader, NOTHING staged in r0..r4 survives into the frag phase --
  * with all-zero uniforms r3/r4 come back as exactly coordX/coordY (px+0.5,
  * py+0.5), which is a confusing value to debug. Fragment shaders must treat
  * r0..r4 as caller-clobbered; borgc already does (regalloc.rs pre-colours
  * r0..r3 for gl_Position and reserves r4 as the perspective-divide scratch).
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

  /** The draw front end's per-pixel program (docs/B1_geometry_front_end.md),
    * used when DRAW_CFG selects it. Uniforms are the first words of the
    * triangle's record, written by BorgSetupRom (see its Record layout):
    * planes a*x + b*y + c for E0..E2 and Zn, then depth scale, depth offset
    * and 1.0. r30/r31 are the pixel centre.
    *
    * Leaves, for the dispatcher's coverage snoop and then the fragment
    * shader:
    *   r0..r2  E0..E2, screen-linear (Ek*Wk is the noperspective barycentric)
    *   r3, r4  Zn = z_ndc and Zf = 1 - Zn, the near and far half-spaces
    *   r5..r7  perspective-correct barycentrics Ek / sum(E)
    *   r8      FragCoord.w = sum(E) = 1/w
    *   r29     FragCoord.z = Zn*scale + offset, also the fragment's depth
    * and clobbers r9, r10. Nothing writes r0..r4 after its plane value: the
    * dispatcher keeps the last value each register gets in this phase.
    */
  val drawInstructions: Seq[BigInt] = {
    import Instructions._
    val U2 = 2; val U3 = 3
    val px = 30; val py = 31; val t = 9; val t2 = 10
    val R = BorgSetupRom.Record
    val p = Seq.newBuilder[BigInt]
    for (k <- 0 until 4) {                       // E0, E1, E2, Zn -> r0..r3
      p += MUL(rs1 = py, rs2 = R.plane(k) + 1, rd = t, funct3 = U2)
      p += ADD(rs1 = t, rs2 = R.plane(k) + 2, rd = t, funct3 = U2)
      p += FMA(rs1 = px, rs2 = R.plane(k), rs3 = t, rd = k, funct3 = U2)
    }
    p += FNEG(rs1 = 3, rd = t)                   // Zf = 1 - Zn
    p += ADD(rs1 = t, rs2 = R.One, rd = 4, funct3 = U2)
    p += ADD(rs1 = 0, rs2 = 1, rd = 8)           // 1/w = E0 + E1 + E2
    p += ADD(rs1 = 8, rs2 = 2, rd = 8)
    p += FRCP(rs1 = 8, rd = t)                   // w, plus one Newton step
    p += FNEG(rs1 = 8, rd = t2)
    p += FMA(rs1 = t2, rs2 = t, rs3 = R.One, rd = t2, funct3 = U3)
    p += FMA(rs1 = t, rs2 = t2, rs3 = t, rd = t)
    for (k <- 0 until 3) p += MUL(rs1 = k, rs2 = t, rd = 5 + k)
    p += MUL(rs1 = 3, rs2 = R.DepthScale, rd = 29, funct3 = U2)
    p += ADD(rs1 = 29, rs2 = R.DepthOffset, rd = 29, funct3 = U2)
    p += BigInt(0)                               // HALT
    p.result()
  }
}
