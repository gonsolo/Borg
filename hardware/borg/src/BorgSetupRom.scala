// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** BorgSetupRom -- triangle setup for the draw front end, as a program on the
  * shader core baked into ROM, the same way BorgRasterRom bakes the per-pixel
  * edge test. See docs/B1_geometry_front_end.md.
  *
  * 2D homogeneous rasterization (Olano and Greer, 1997). With the viewport
  * folded into the clip coordinates,
  *
  *   X'k = Xk*sx + Wk*ox,   Y'k = Yk*sy + Wk*oy,   M = [X'; Y'; W]  (columns = corners)
  *
  * the rows of M^-1 are three screen-space planes Ek(x, y) = ak*x + bk*y + ck
  * whose values are the perspective-correct barycentrics divided by w. A
  * pixel is inside when all three are >= 0, which is exact even for corners
  * behind the eye, so there is no clipping. z_ndc = sum(Ek*Zk) is linear in
  * screen space too; its plane Zn is the near-plane half-space, and 1 - Zn
  * the far one. Row k of M^-1 is the cross product of the other two columns
  * over det M, and the sign of det M is the facing.
  *
  * Inputs, staged into the uniform bank by the draw walker:
  *   u0..u11  X, Y, Z, W of corners 0, 1, 2
  *   u12, u13 viewport scale sx, sy     u14, u15 viewport offset ox, oy
  *   u16, u17 depth scale, depth offset (FragCoord.z = z_ndc*scale + offset)
  *   u18      1.0
  *   u19..u21 -0.125, -0.375, +0.375   (the standard 4x sample offsets;
  *            all 0 for a single-sample render)
  *   u22, u23 depth bias slope and constant factors (FP32)
  *   u24      r's floor: 2^-16 for D16, 0 for D32_SFLOAT
  *
  * Outputs:
  *   r0..r5   screen x, y of corners 0..2 (the bounding box; meaningless
  *            when some W <= 0, where the walker bins the whole viewport)
  *   r6       det M (the walker culls on its sign and on zero)
  *   record   via SOUT, stride 1 (see [[BorgSetupRom.Record]])
  *
  * r30/r31 read 0 here (a sequencer-run program), and r31 is the zero the
  * register copies below add. Temporaries never use r0..r6, whose final
  * values the walker snoops.
  */
private[borg] object BorgSetupRom {
  /** Record word indices (SOUT indices; the record's first 32 words are what
    * pass 2 DMAs into the uniform bank for the raster ROM). */
  object Record {
    def plane(k: Int): Int = 3 * k   // a, b, c of E0..E2 at 0..8, Zn at 9..11
    val DepthScale  = 12
    val DepthOffset = 13
    val One         = 14
    /** Written by the draw walker: bit 1 = back-facing. Pass 2 snoops it. */
    val Meta        = 15
    /** |1/det M|: turns the edge planes' sum into FragCoord.w = 1/w. */
    val InvDet      = 16
    /** The depth plane's two MSAA sample deltas in framebuffer depth
      * (times DEPTH_SCALE): sample depths are FragCoord.z + {d0, d1, -d1, -d0}. */
    val SampleDepth = 17
    /** Words 0 .. Image-1 are what pass 2 DMAs into the uniform bank. */
    val Image       = 20
    /** The uniform window a stage's own constants live in (DRAW_VS_CONST,
      * DRAW_FS_CONST): above the hardware's per-triangle words. */
    val VsConstFirst = 25    // above the setup ROM's inputs
    val FsConstFirst = Image // above the record image
    /** MSAA sample deltas of plane k (E0..E2, Zn): d0 at 32 + 2k, d1 after. */
    def covDelta(k: Int): Int = 32 + 2 * k
    val Words = 40
  }

  // Uniform inputs.
  val uX = Seq(0, 4, 8); val uY = Seq(1, 5, 9); val uZ = Seq(2, 6, 10); val uW = Seq(3, 7, 11)
  val uSx = 12; val uSy = 13; val uOx = 14; val uOy = 15
  val uDepthScale = 16; val uDepthOffset = 17; val uOne = 18
  val uM0125 = 19; val uM0375 = 20; val uP0375 = 21
  val uBiasSlope = 22; val uBiasConst = 23; val uRFloor = 24
  val Uniforms = 25

  val instructions: Seq[BigInt] = {
    import Instructions._
    // funct3: which operand is a uniform (1 = rs1, 2 = rs2, 3 = rs3).
    val U1 = 1; val U2 = 2; val U3 = 3
    val zero = 31
    val rW  = Seq(9, 10, 11);  val rX  = Seq(12, 13, 14); val rY = Seq(15, 16, 17)
    val rNW = Seq(18, 19, 20)
    val rSignMask = 21; val rDetSign = 22; val rAbsInv = 23
    val rSx = 7; val rSy = 8          // until the corners are transformed
    val rInvDet = 7; val t = 8        // after
    val (ra, rb, rc) = (24, 25, 26)   // the plane being built
    val (za, zb, zc) = (27, 28, 29)   // Zn, accumulated over the three planes
    val p = Seq.newBuilder[BigInt]

    // 1/x: the ~11-bit FRCP estimate plus Newton steps, r = r*(2 - x*r),
    // written r + r*(1 - x*r) so it needs only 1.0. One step gives ~22 bits
    // (the bounding box); two give FP32 (det M, which scales the depth
    // plane: one step left a flat z = 0.5 some 2^-20 low, more than a
    // D32_SFLOAT ulp). tmp must differ from negX when steps > 1.
    def rcp(x: Int, negX: Int, r: Int, tmp: Int, steps: Int = 1): Unit = {
      p += FRCP(rs1 = x, rd = r)
      for (_ <- 0 until steps) {
        p += FMA(rs1 = negX, rs2 = r, rs3 = uOne, rd = tmp, funct3 = U3)
        p += FMA(rs1 = r, rs2 = tmp, rs3 = r, rd = r)
      }
    }
    // The two MSAA sample deltas of plane (a, b): d0 = -0.125a - 0.375b,
    // d1 = 0.375a - 0.125b; samples 2 and 3 are their negations.
    def covDelta(k: Int, a: Int, b: Int): Unit = {
      p += MUL(rs1 = uM0375, rs2 = b, rd = t, funct3 = U1)
      p += FMA(rs1 = uM0125, rs2 = a, rs3 = t, rd = t, funct3 = U1)
      p += SOUT(rs2 = t, index = Record.covDelta(k))
      p += MUL(rs1 = uM0125, rs2 = b, rd = t, funct3 = U1)
      p += FMA(rs1 = uP0375, rs2 = a, rs3 = t, rd = t, funct3 = U1)
      p += SOUT(rs2 = t, index = Record.covDelta(k) + 1)
    }

    // Depth bias, Vulkan's o = m * slope + r * constant, folded into the
    // triangle's depth offset so every sample of every pixel carries it.
    // m = max(|dz/dx|, |dz/dy|) of FragCoord.z (the approximation the spec
    // allows); r = 2^-16 for D16, and for D32_SFLOAT 2^(e - 23), e the
    // exponent of the triangle's largest |depth| -- exactly ulp(max z),
    // computed as asFloat(bits + 1) - z. The largest depth is the Zn plane at
    // the three corners, or the far plane 1.0 when a corner is behind the eye
    // (its screen position is meaningless). There is no float max or abs:
    // non-negative floats order like unsigned integers, and |x| clears the
    // sign bit. With both factors 0 (reset) the offset is unchanged, since m
    // and r are clamped finite (z to 1.0, so r <= 2^-23).
    val rBiasedOffset = 26
    def depthBias(): Unit = {
      val (c1, maxFin, m, zmax, a, b, c, d, rr) = (12, 13, 14, 15, 16, 17, 18, 19, 20)
      def abs(src: Int, dst: Int, f: Int = 0): Unit = {       // src may be dst
        p += IAND(rs1 = src, rs2 = rSignMask, rd = d, funct3 = f)
        p += IXOR(rs1 = src, rs2 = d, rd = dst, funct3 = f)
      }
      // dst = cond ? y : x, cond an integer 0/1 in `k`; clobbers k and d.
      def pick(k: Int, x: Int, y: Int, dst: Int): Unit = {
        p += ISUB(rs1 = zero, rs2 = k, rd = k)
        p += IXOR(rs1 = x, rs2 = y, rd = d)
        p += IAND(rs1 = d, rs2 = k, rd = d)
        p += IXOR(rs1 = x, rs2 = d, rd = dst)
      }
      def maxU(x: Int, y: Int, dst: Int): Unit = { p += ISLTU(rs1 = x, rs2 = y, rd = c); pick(c, x, y, dst) }
      def minU(x: Int, y: Int, dst: Int): Unit = { p += ISLTU(rs1 = y, rs2 = x, rd = c); pick(c, x, y, dst) }
      p += ISEQ(rs1 = zero, rs2 = zero, rd = c1)                          // integer 1
      p += ISHL(rs1 = uOne, rs2 = c1, rd = maxFin, funct3 = U1)           // 0x7F000000
      p += IOR(rs1 = maxFin, rs2 = uOne, rd = maxFin, funct3 = U2)        // 0x7F800000, +inf
      p += ISUB(rs1 = maxFin, rs2 = c1, rd = maxFin)                      // largest finite
      // m
      abs(za, a); abs(zb, b); maxU(a, b, m)
      abs(uDepthScale, a, U1)
      p += MUL(rs1 = m, rs2 = a, rd = m)
      minU(m, maxFin, m)
      // The largest |FragCoord.z| over the corners.
      for (k <- 0 until 3) {
        p += FMA(rs1 = za, rs2 = 2 * k, rs3 = zc, rd = a)
        p += FMA(rs1 = zb, rs2 = 2 * k + 1, rs3 = a, rd = a)
        p += MUL(rs1 = a, rs2 = uDepthScale, rd = a, funct3 = U2)
        p += ADD(rs1 = a, rs2 = uDepthOffset, rd = a, funct3 = U2)
        if (k == 0) abs(a, zmax) else { abs(a, a); maxU(zmax, a, zmax) }
      }
      // A corner with W <= +0 (as a signed integer, below 1): the far plane.
      p += ISLT(rs1 = rW(0), rs2 = c1, rd = b)
      for (k <- 1 until 3) { p += ISLT(rs1 = rW(k), rs2 = c1, rd = a); p += IOR(rs1 = b, rs2 = a, rd = b) }
      p += ADD(rs1 = uOne, rs2 = zero, rd = a, funct3 = U1)
      pick(b, zmax, a, zmax)
      minU(zmax, a, zmax)                    // depth is at most the far plane
      // r = max(floor, ulp(zmax))
      p += IADD(rs1 = zmax, rs2 = c1, rd = rr)
      p += FNEG(rs1 = zmax, rd = a)
      p += ADD(rs1 = rr, rs2 = a, rd = rr)
      p += ADD(rs1 = uRFloor, rs2 = zero, rd = a, funct3 = U1)
      maxU(a, rr, rr)
      // offset + m * slope + r * constant
      p += MUL(rs1 = uBiasConst, rs2 = rr, rd = rr, funct3 = U1)
      p += FMA(rs1 = uBiasSlope, rs2 = m, rs3 = rr, rd = rr, funct3 = U1)
      p += ADD(rs1 = uDepthOffset, rs2 = rr, rd = rBiasedOffset, funct3 = U1)
    }

    // Corners into registers, with the viewport folded in.
    p += ADD(rs1 = uSx, rs2 = zero, rd = rSx, funct3 = U1)
    p += ADD(rs1 = uSy, rs2 = zero, rd = rSy, funct3 = U1)
    for (k <- 0 until 3) p += ADD(rs1 = uW(k), rs2 = zero, rd = rW(k), funct3 = U1)
    for (k <- 0 until 3) {
      p += MUL(rs1 = rW(k), rs2 = uOx, rd = ra, funct3 = U2)
      p += FMA(rs1 = uX(k), rs2 = rSx, rs3 = ra, rd = rX(k), funct3 = U1)
      p += MUL(rs1 = rW(k), rs2 = uOy, rd = ra, funct3 = U2)
      p += FMA(rs1 = uY(k), rs2 = rSy, rs3 = ra, rd = rY(k), funct3 = U1)
    }
    for (k <- 0 until 3) p += FNEG(rs1 = rW(k), rd = rNW(k))

    // Screen positions for the bounding box: X'/W, Y'/W.
    for (k <- 0 until 3) {
      rcp(rW(k), rNW(k), ra, rb)
      p += MUL(rs1 = rX(k), rs2 = ra, rd = 2 * k)
      p += MUL(rs1 = rY(k), rs2 = ra, rd = 2 * k + 1)
    }

    // Edge plane k = (column i) x (column j), i and j the other two corners,
    // with its sign flipped when det M < 0 so that inside is always >= 0.
    //
    // NOT divided by det M, and every component is round(p) - round(q) of
    // two separately rounded products (no FMA): a triangle sharing the edge
    // computes (column j) x (column i), and round(q) - round(p) is then the
    // exact negation. With the per-pixel evaluation and the MSAA deltas also
    // sign-symmetric, the two triangles' edge values are exact opposites at
    // every sample -- which is what makes the tie rule on exact zeros
    // watertight (no sample in both triangles, none in neither). Dividing
    // by each triangle's own det M rounded the two sides differently. The
    // barycentrics do not need the division: E_k / sum(E) cancels it.
    for (k <- 0 until 3) {
      val i = (k + 1) % 3; val j = (k + 2) % 3
      p += MUL(rs1 = rY(i), rs2 = rW(j), rd = ra)                   // Yi*Wj - Wi*Yj
      p += MUL(rs1 = rNW(i), rs2 = rY(j), rd = t)
      p += ADD(rs1 = ra, rs2 = t, rd = ra)
      p += MUL(rs1 = rW(i), rs2 = rX(j), rd = rb)                   // Wi*Xj - Xi*Wj
      p += MUL(rs1 = rX(i), rs2 = rNW(j), rd = t)
      p += ADD(rs1 = rb, rs2 = t, rd = rb)
      p += MUL(rs1 = rX(i), rs2 = rY(j), rd = rc)                   // Xi*Yj - Yi*Xj
      p += MUL(rs1 = rY(i), rs2 = rX(j), rd = t)
      p += FNEG(rs1 = t, rd = t)
      p += ADD(rs1 = rc, rs2 = t, rd = rc)
      if (k == 0) {
        // det M = column 0 . (column 1 x column 2): its sign is the facing,
        // |1/det| scales the planes' sum into 1/w.
        p += MUL(rs1 = rX(0), rs2 = ra, rd = 6)
        p += FMA(rs1 = rY(0), rs2 = rb, rs3 = 6, rd = 6)
        p += FMA(rs1 = rW(0), rs2 = rc, rs3 = 6, rd = 6)
        // The sign bit: -1.0 XOR 1.0. (FNEG of 0 is +0 -- it goes through
        // the FMA -- so -0.0 cannot supply it.)
        p += FNEG(rs1 = uOne, rd = rSignMask, funct3 = U1)
        p += IXOR(rs1 = rSignMask, rs2 = uOne, rd = rSignMask, funct3 = U2)
        p += IAND(rs1 = 6, rs2 = rSignMask, rd = rDetSign)
        p += FNEG(rs1 = 6, rd = t)
        rcp(6, t, rInvDet, rAbsInv, steps = 2)             // rAbsInv is free until below
        p += IAND(rs1 = rInvDet, rs2 = rSignMask, rd = rAbsInv)
        p += IXOR(rs1 = rInvDet, rs2 = rAbsInv, rd = rAbsInv)           // |1/det|
      }
      for (r <- Seq(ra, rb, rc)) p += IXOR(rs1 = r, rs2 = rDetSign, rd = r)
      p += SOUT(rs2 = ra, index = Record.plane(k))
      p += SOUT(rs2 = rb, index = Record.plane(k) + 1)
      p += SOUT(rs2 = rc, index = Record.plane(k) + 2)
      // Zn = sum(Zk * Ek) / |det| (the normalized planes carry det's sign)
      for ((acc, src) <- Seq(za -> ra, zb -> rb, zc -> rc)) {
        if (k == 0) p += MUL(rs1 = uZ(0), rs2 = src, rd = acc, funct3 = U1)
        else        p += FMA(rs1 = uZ(k), rs2 = src, rs3 = acc, rd = acc, funct3 = U1)
      }
      covDelta(k, ra, rb)
    }
    for (acc <- Seq(za, zb, zc)) p += MUL(rs1 = acc, rs2 = rAbsInv, rd = acc)
    p += SOUT(rs2 = za, index = Record.plane(3))
    p += SOUT(rs2 = zb, index = Record.plane(3) + 1)
    p += SOUT(rs2 = zc, index = Record.plane(3) + 2)
    covDelta(3, za, zb)
    // The same two deltas scaled into framebuffer depth, for per-sample depth.
    p += MUL(rs1 = uM0375, rs2 = zb, rd = t, funct3 = U1)
    p += FMA(rs1 = uM0125, rs2 = za, rs3 = t, rd = t, funct3 = U1)
    p += MUL(rs1 = t, rs2 = uDepthScale, rd = t, funct3 = U2)
    p += SOUT(rs2 = t, index = Record.SampleDepth)
    p += MUL(rs1 = uM0125, rs2 = zb, rd = t, funct3 = U1)
    p += FMA(rs1 = uP0375, rs2 = za, rs3 = t, rd = t, funct3 = U1)
    p += MUL(rs1 = t, rs2 = uDepthScale, rd = t, funct3 = U2)
    p += SOUT(rs2 = t, index = Record.SampleDepth + 1)
    p += SOUT(rs2 = rAbsInv, index = Record.InvDet)
    p += SOUT(rs2 = uDepthScale,  index = Record.DepthScale,  funct3 = U2)
    depthBias()
    p += SOUT(rs2 = rBiasedOffset, index = Record.DepthOffset)
    p += SOUT(rs2 = uOne,         index = Record.One,         funct3 = U2)
    p += BigInt(0)                                                  // HALT
    p.result()
  }
}
