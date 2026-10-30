// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Triangle setup for the draw front end, a shader-core program in ROM
  * (docs/B1_geometry_front_end.md).
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
  *   u19..u21 -0.125, -0.375, +0.375   (4x sample offsets; 0 for one sample)
  *   u22, u23 depth bias slope and constant factors (FP32)
  *   u24      r's floor: 2^-15 for D16, 0 for D32_SFLOAT
  *
  * Outputs:
  *   setup_screen0..5  screen x, y of corners 0..2 (meaningless when some W <= 0,
  *            where the walker bins the whole viewport)
  *   setup_det         det M (the walker culls on its sign and on zero)
  *   record   via SOUT (see [[BorgSetupRom.Record]])
  */
private[borg] object BorgSetupRom {
  /** SOUT indices of the setup record. */
  object Record {
    def plane(k: Int): Int = 3 * k   // a, b, c of E0..E2 at 0..8, Zn at 9..11
    val DepthScale  = 12
    val DepthOffset = 13
    val One         = 14
    val Meta        = 15     // written by the draw walker: bit 1 = back-facing; pass 2 snoops it
    val InvDet      = 16     // |1/det M|: turns the edge planes' sum into FragCoord.w = 1/w
    // Sample depths are FragCoord.z + {d0, d1, -d1, -d0}, in framebuffer depth.
    val SampleDepth = 17
    val Image       = 20     // words 0 .. Image-1 are what pass 2 DMAs into the uniform bank
    // First uniform of DRAW_VS_CONST / DRAW_FS_CONST, above the hardware's per-triangle words.
    val VsConstFirst = 25    // above the setup ROM's inputs
    val FsConstFirst = Image // above the record image
    // MSAA sample deltas of plane k (E0..E2, Zn): d0 at 32 + 2k, d1 after.
    def covDelta(k: Int): Int = 32 + 2 * k
    val Words = 40
  }

  val uX = Seq(0, 4, 8); val uY = Seq(1, 5, 9); val uZ = Seq(2, 6, 10); val uW = Seq(3, 7, 11)
  val uSx = 12; val uSy = 13; val uOx = 14; val uOy = 15
  val uDepthScale = 16; val uDepthOffset = 17; val uOne = 18
  val uM0125 = 19; val uM0375 = 20; val uP0375 = 21
  val uBiasSlope = 22; val uBiasConst = 23; val uRFloor = 24
  val Uniforms = 25

  private val program: (Seq[BigInt], Int) = {
    import Instructions._
    import Opcodes._
    val uu = 1; val rOne = 2          // a uniform word read by CSR; 1.0
    val rS = Seq(3, 4, 5, 6, 7, 22)   // screen x, y of corners 0..2, from after the planes
    val rW  = Seq(9, 10, 11);  val rX  = Seq(12, 13, 14); val rY = Seq(15, 16, 17)
    val rNW = Seq(18, 19, 20)
    val rSignMask = 21; val rDetSign = 22; val rAbsInv = 23
    val rSx = 7; val rSy = 8          // until the corners are transformed
    val rInvDet = 7; val t = 8        // after
    val rDet = 6
    val (ra, rb, rc) = (24, 25, 26)   // the plane being built
    val (za, zb, zc) = (27, 28, 29)   // Zn, accumulated over the three planes
    val p = scala.collection.mutable.ArrayBuffer[BigInt]()
    def ld(rd: Int, k: Int): Unit = p += CSRRS(rd, 0, Csr.Uniform0 + k)
    // A copy through FADD with +0.0, as v1's window copy (-0.0 becomes +0.0).
    def ldAdd(rd: Int, k: Int): Unit = { ld(uu, k); p += FADD_S(rd, uu, 0) }
    def seq(rd: Int, a: Int, b: Int): Unit = { p += XOR(rd, a, b); p += SLTIU(rd, rd, 1) }

    // 1/x: the ~11-bit FRCP estimate plus Newton steps r + r*(1 - x*r). One step gives ~22 bits
    // (the bounding box), two give FP32 (det M scales the depth plane, D32_SFLOAT needs an ulp).
    // tmp must differ from negX when steps > 1.
    def rcp(x: Int, negX: Int, r: Int, tmp: Int, steps: Int = 1): Unit = {
      p += FRCP_V2(r, x)
      for (_ <- 0 until steps) {
        p += FMADD_S(tmp, negX, r, rOne)
        p += FMADD_S(r, r, tmp, r)
      }
    }
    // The two MSAA sample deltas of plane (a, b): d0 = -0.125a - 0.375b,
    // d1 = 0.375a - 0.125b; samples 2 and 3 are their negations.
    def delta(a: Int, b: Int, d: Int): Unit = {
      val (pa, pb) = if (d == 0) (uM0125, uM0375) else (uP0375, uM0125)
      ld(uu, pb); p += FMUL_S(t, uu, b)
      ld(uu, pa); p += FMADD_S(t, uu, a, t)
    }
    def covDelta(k: Int, a: Int, b: Int): Unit = {
      delta(a, b, 0); p += SOUT(rs2 = t, index = Record.covDelta(k))
      delta(a, b, 1); p += SOUT(rs2 = t, index = Record.covDelta(k) + 1)
    }

    // Depth bias, Vulkan's o = m * slope + r * constant, folded into the triangle's depth offset.
    // m = max(|dz/dx|, |dz/dy|) (the approximation the spec allows); r = 2^-15 for D16, and for
    // D32_SFLOAT ulp(max |z|) = asFloat(bits + 1) - z, the far plane 1.0 when a corner is behind
    // the eye. No float max or abs: non-negative floats order like unsigned integers. m and r are
    // clamped finite, so zero factors leave the offset unchanged.
    val rBiasedOffset = 26
    def depthBias(): Unit = {
      val (c1, maxFin, m, zmax, a, b, c, d, rr) = (12, 13, 14, 15, 16, 17, 18, 19, 20)
      def abs(src: Int, dst: Int): Unit = {       // src may be dst
        p += AND(d, src, rSignMask)
        p += XOR(dst, src, d)
      }
      // dst = cond ? y : x, cond an integer 0/1 in `k`; clobbers k and d.
      def pick(k: Int, x: Int, y: Int, dst: Int): Unit = {
        p += SUB(k, 0, k)
        p += XOR(d, x, y)
        p += AND(d, d, k)
        p += XOR(dst, x, d)
      }
      def maxU(x: Int, y: Int, dst: Int): Unit = { p += SLTU(c, x, y); pick(c, x, y, dst) }
      def minU(x: Int, y: Int, dst: Int): Unit = { p += SLTU(c, y, x); pick(c, x, y, dst) }
      p += ADDI(c1, 0, 1)                                                 // integer 1
      p += LUI(maxFin, 0x7F800)                                           // +inf
      p += ADDI(maxFin, maxFin, 0xFFF)                                    // largest finite
      // m
      abs(za, a); abs(zb, b); maxU(a, b, m)
      ld(a, uDepthScale); abs(a, a)
      p += FMUL_S(m, m, a)
      minU(m, maxFin, m)
      // The largest |FragCoord.z| over the corners.
      for (k <- 0 until 3) {
        p += FMADD_S(a, za, rS(2 * k), zc)
        p += FMADD_S(a, zb, rS(2 * k + 1), a)
        ld(uu, uDepthScale); p += FMUL_S(a, a, uu)
        ld(uu, uDepthOffset); p += FADD_S(a, a, uu)
        if (k == 0) abs(a, zmax) else { abs(a, a); maxU(zmax, a, zmax) }
      }
      // A corner with W <= +0 (as a signed integer, below 1): the far plane.
      p += SLT(b, rW(0), c1)
      for (k <- 1 until 3) { p += SLT(a, rW(k), c1); p += OR(b, b, a) }
      p += FADD_S(a, rOne, 0)
      pick(b, zmax, a, zmax)
      minU(zmax, a, zmax)                    // depth is at most the far plane
      // r = max(floor, ulp(zmax))
      p += ADD(rr, zmax, c1)
      p += FNEG(rs1 = zmax, rd = a)
      p += FADD_S(rr, rr, a)
      ldAdd(a, uRFloor)
      maxU(a, rr, rr)
      // offset + m * slope + r * constant
      ld(uu, uBiasConst); p += FMUL_S(rr, uu, rr)
      ld(uu, uBiasSlope); p += FMADD_S(rr, uu, m, rr)
      ld(uu, uDepthOffset); p += FADD_S(rBiasedOffset, uu, rr)
    }

    // Corners into registers, with the viewport folded in.
    ld(rOne, uOne)
    ldAdd(rSx, uSx); ldAdd(rSy, uSy)
    for (k <- 0 until 3) ldAdd(rW(k), uW(k))
    for (k <- 0 until 3) {
      ld(uu, uOx); p += FMUL_S(ra, rW(k), uu)
      ld(uu, uX(k)); p += FMADD_S(rX(k), uu, rSx, ra)
      ld(uu, uOy); p += FMUL_S(ra, rW(k), uu)
      ld(uu, uY(k)); p += FMADD_S(rY(k), uu, rSy, ra)
    }
    for (k <- 0 until 3) p += FNEG(rs1 = rW(k), rd = rNW(k))

    // A corner in front of the eye snaps to the 1/256 pixel the limits report
    // (subPixelPrecisionBits): X' = round(X'/W * 256) / 256 * W, Y' alike. Adding and
    // subtracting 1.5 * 2^15 rounds to 2^-8.
    locally {
      val (magic, negMagic, r, tmp, v, mask) = (21, 22, 23, 24, 25, 26)
      p += LUI(magic, 0x47400); p += LUI(negMagic, 0xC7400)
      for (k <- 0 until 3) {
        rcp(rW(k), rNW(k), r, tmp, steps = 2)
        p += SLT(mask, 0, rW(k))                                       // W > +0, as an integer
        p += SUB(mask, 0, mask)
        for (c <- Seq(rX(k), rY(k))) {
          p += FMUL_S(v, c, r)
          p += FADD_S(v, v, magic); p += FADD_S(v, v, negMagic)
          p += FMUL_S(v, v, rW(k))
          p += XOR(v, c, v); p += AND(v, v, mask)
          p += XOR(c, c, v)
        }
      }
    }

    // Edge plane k = (column i) x (column j), sign-flipped when det M < 0 so inside is >= 0.
    // Not divided by det M and no FMA: each component is round(p) - round(q), so a triangle
    // sharing the edge gets the exact negation and the tie rule on zeros is watertight.
    // The barycentrics E_k / sum(E) cancel the missing division.
    for (k <- 0 until 3) {
      val i = (k + 1) % 3; val j = (k + 2) % 3
      p += FMUL_S(ra, rY(i), rW(j))                   // Yi*Wj - Wi*Yj
      p += FMUL_S(t, rNW(i), rY(j))
      p += FADD_S(ra, ra, t)
      p += FMUL_S(rb, rW(i), rX(j))                   // Wi*Xj - Xi*Wj
      p += FMUL_S(t, rX(i), rNW(j))
      p += FADD_S(rb, rb, t)
      p += FMUL_S(rc, rX(i), rY(j))                   // Xi*Yj - Yi*Xj
      p += FMUL_S(t, rY(i), rX(j))
      p += FNEG(rs1 = t, rd = t)
      p += FADD_S(rc, rc, t)
      if (k == 0) {
        // det M = column 0 . (column 1 x column 2): its sign is the facing,
        // |1/det| scales the planes' sum into 1/w.
        p += FMUL_S(rDet, rX(0), ra)
        p += FMADD_S(rDet, rY(0), rb, rDet)
        p += FMADD_S(rDet, rW(0), rc, rDet)
        p += CSRRW(0, rDet, Csr.SetupDet)
        p += LUI(rSignMask, 0x80000)
        p += AND(rDetSign, rDet, rSignMask)
        p += FNEG(rs1 = rDet, rd = t)
        rcp(rDet, t, rInvDet, rAbsInv, steps = 2)          // rAbsInv is free until below
        p += AND(rAbsInv, rInvDet, rSignMask)
        p += XOR(rAbsInv, rInvDet, rAbsInv)           // |1/det|
      }
      for (r <- Seq(ra, rb, rc)) p += XOR(r, r, rDetSign)
      p += SOUT(rs2 = ra, index = Record.plane(k))
      p += SOUT(rs2 = rb, index = Record.plane(k) + 1)
      p += SOUT(rs2 = rc, index = Record.plane(k) + 2)
      // Zn = sum(Zk * Ek) / |det| (the normalized planes carry det's sign)
      ld(uu, uZ(k))
      for ((acc, src) <- Seq(za -> ra, zb -> rb, zc -> rc)) {
        if (k == 0) p += FMUL_S(acc, uu, src)
        else        p += FMADD_S(acc, uu, src, acc)
      }
      covDelta(k, ra, rb)
    }
    for (acc <- Seq(za, zb, zc)) p += FMUL_S(acc, acc, rAbsInv)

    // Screen positions for the bounding box: X'/W, Y'/W.
    for (k <- 0 until 3) {
      rcp(rW(k), rNW(k), ra, rb)
      p += FMUL_S(rS(2 * k), rX(k), ra)
      p += FMUL_S(rS(2 * k + 1), rY(k), ra)
    }
    for (r <- 0 until 6) p += CSRRW(0, rS(r), Csr.SetupScreen0 + r)

    // A primitive in the far plane (Z = W at every corner: a skybox) gets the exact plane Zn = 1:
    // from rounded coefficients it misses Zf >= 0 and LEQUAL against a 1.0 clear by an ulp.
    locally {
      val (f, g, m) = (12, 13, 14)
      ld(uu, uZ(0)); seq(f, uu, rW(0))
      for (k <- 1 until 3) {
        ld(uu, uZ(k)); seq(g, uu, rW(k))
        p += AND(f, f, g)
      }
      p += SUB(m, 0, f)                                             // all ones if so
      for (acc <- Seq(za, zb)) {                                    // a, b = 0
        p += AND(g, acc, m)
        p += XOR(acc, acc, g)
      }
      p += XOR(g, zc, rOne)                       // c = 1.0
      p += AND(g, g, m)
      p += XOR(zc, zc, g)
    }
    // Flat depth (the same Z and W at every corner: a point's quad) gets the exact plane Zn = Z/W.
    locally {
      val (f, g, m, z, r) = (12, 13, 14, 15, 16)
      ldAdd(z, uZ(0))
      ld(uu, uZ(1)); seq(f, uu, z)
      ld(uu, uZ(2)); seq(g, uu, z)
      p += AND(f, f, g)
      for (k <- 1 until 3) {
        seq(g, rW(0), rW(k))
        p += AND(f, f, g)
      }
      p += SUB(m, 0, f)
      rcp(rW(0), rNW(0), r, g, steps = 2)
      p += FMUL_S(z, z, r)
      for (acc <- Seq(za, zb)) {
        p += AND(g, acc, m)
        p += XOR(acc, acc, g)
      }
      p += XOR(g, zc, z)
      p += AND(g, g, m)
      p += XOR(zc, zc, g)
    }
    p += SOUT(rs2 = za, index = Record.plane(3))
    p += SOUT(rs2 = zb, index = Record.plane(3) + 1)
    p += SOUT(rs2 = zc, index = Record.plane(3) + 2)
    p += SOUT(rs2 = rAbsInv, index = Record.InvDet)
    ld(uu, uDepthScale);  p += SOUT(rs2 = uu, index = Record.DepthScale)
    ld(uu, uDepthOffset); p += SOUT(rs2 = uu, index = Record.DepthOffset)   // unbiased
    p += SOUT(rs2 = rOne, index = Record.One)
    // A single-sample draw without depth bias stops here (BorgCore, setupShort).
    val shortPc = p.length
    depthBias()
    p += SOUT(rs2 = rBiasedOffset, index = Record.DepthOffset)
    covDelta(3, za, zb)
    // The same two deltas scaled into framebuffer depth, for per-sample depth.
    for (d <- 0 until 2) {
      delta(za, zb, d)
      ld(uu, uDepthScale); p += FMUL_S(t, t, uu)
      p += SOUT(rs2 = t, index = Record.SampleDepth + d)
    }
    p += BigInt(0)                                                  // HALT
    (p.toSeq, shortPc)
  }
  val instructions: Seq[BigInt] = program._1
  val shortPc: Int = program._2
}
