// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import utest._

/** Hardware-level proof that borgc's draw-front-end vertex compilation
  * (BORGC_DRAW_MODE, mesa/src/borg/compiler) produces a shader the real
  * hardware runs correctly -- not just one that compiles cleanly.
  *
  * The exact 72 instruction words below are borgc's own output for the real
  * cube.vert (`BORGC_DRAW_MODE=1 borgc-cli -s vert cube_vert.spv`,
  * mesa 7ef718b0619), transcribed from its `BORGC_DUMP_ISA` dump, not
  * hand-written. It replaces [[BorgDrawTests.DrawRig]]'s own hand-written
  * `vs` for one draw, exercising exactly what is new and unverified in that
  * compile: vertex-pulling LOADs of a dynamically-indexed UBO array
  * (position[gl_VertexIndex], attr[gl_VertexIndex]) and a compile-time-
  * constant one chained across four separate load_ubo calls (the MVP
  * columns) through the SAME running-address scheme, the real 4x4 matrix
  * multiply, and SOUT-based varying output resolved through a same-shader
  * load_output (frag_pos = gl_Position.xyz). The fragment side keeps
  * DrawRig's own hand-written, already-proven `fs` (plain FATTR +
  * barycentric FMUL/FMADD, structurally identical to what borgc's
  * draw-mode load_input codegen emits) -- this test's whole point is the
  * vertex compile, not a second, redundant check of FATTR itself.
  *
  * cube.vert's own UBO layout (`layout(std140, binding=0) uniform buf {
  * mat4 MVP; vec4 position[36]; vec4 attr[36]; }`) is reproduced exactly:
  * MVP at word 0 (column-major), position[] at word 16 (byte 64),
  * attr[] at word 160 (byte 640), relative to LS_BASE -- which nothing
  * else in DrawRig's default `draw()` sequence points anywhere, so this
  * sets it explicitly via the `extra` register list, alongside the
  * pinned integer constants (r5..r9) the compile's own header says the
  * firmware must load into GPRs before running: word stride 4, MVP
  * base 0, position base 16, attr base 160, and the shared +1 increment
  * (see borgc's `const regs (firmware-staged via MMIO)` log line).
  *
  * MVP = diag(2, 2, 1, 1): a real, non-identity matrix (so a wrong LOAD
  * address or a wrong matmul term shows up as a wrong answer, not an
  * accidental pass-through), simple enough that `position[i] =
  * clip[i] / (2, 2, 1, 1)` inverts it by hand for the test's own input,
  * while the reference stays the clip-space vertices themselves
  * (`BorgDrawTests.planes`/`eval`, the same double-precision reference
  * every other BorgDrawTests scenario checks against).
  */
object BorgDrawFrontEndCompilerTests extends TestSuite {
  import BorgDrawTests.{DrawRig, V, at, planes, eval, run}

  /** borgc-cli -s vert -H ... cube_vert.spv, BORGC_DRAW_MODE=1, 72 words.
    * Scheduled by opt.rs's schedule_for_pressure (interleaves the MVP/
    * position LOAD chains with the matmul instead of loading all 16 MVP
    * words up front) -- a provably-equivalent reordering, not a logic
    * change; see opt.rs's own doc for why it exists. */
  val compiledVert: Seq[BigInt] = Seq(
      BigInt("285F0200",16), BigInt("1C620500",16), BigInt("1C750200",16), BigInt("44050580",16), BigInt("1C720500",16), BigInt("44020600",16),
      BigInt("1C750200",16), BigInt("44050680",16), BigInt("44020500",16), BigInt("84B00000",16), BigInt("84C00080",16), BigInt("84D00100",16),
      BigInt("84A00180",16), BigInt("285F0500",16), BigInt("1C950680",16), BigInt("1C768500",16), BigInt("1C750200",16), BigInt("1C720600",16),
      BigInt("44060580",16), BigInt("1C740600",16), BigInt("1C760700",16), BigInt("1C770780",16), BigInt("1C778800",16), BigInt("1C780880",16),
      BigInt("1C788900",16), BigInt("1C790980",16), BigInt("1C798A00",16), BigInt("1C7A0A80",16), BigInt("1C7A8B00",16), BigInt("1C7B0B80",16),
      BigInt("1C7B8C00",16), BigInt("44040C80",16), BigInt("44080D00",16), BigInt("440A0800",16), BigInt("44068A00",16), BigInt("44050680",16),
      BigInt("44020500",16), BigInt("44060200",16), BigInt("44088600",16), BigInt("440A8880",16), BigInt("1C7C0A80",16), BigInt("440C0D80",16),
      BigInt("08BD8C00",16), BigInt("C0A80D84",16), BigInt("D8DD0C04",16), BigInt("C14C8004",16), BigInt("44070C00",16), BigInt("44090700",16),
      BigInt("440B0900",16), BigInt("1C7A8B00",16), BigInt("440A8D80",16), BigInt("08BD8A80",16), BigInt("A8A88D84",16), BigInt("D8D60A84",16),
      BigInt("A9420084",16), BigInt("44078A80",16), BigInt("44098780",16), BigInt("440B8980",16), BigInt("1C7B0B80",16), BigInt("440B0D80",16),
      BigInt("08BD8B00",16), BigInt("B0A90D84",16), BigInt("D8D70B04",16), BigInt("B14C0104",16), BigInt("440B8B00",16), BigInt("08BB0B80",16),
      BigInt("B8A98B04",16), BigInt("B0D78B84",16), BigInt("B94A8184",16), BigInt("84000200",16), BigInt("84100280",16), BigInt("84200300",16),
  )

  // Pinned integer constants borgc's own log names for this exact compile:
  // "const regs (firmware-staged via MMIO): r5=0x0004 r6=0x00a0 r7=0x0001
  //  r8=0x0000 r9=0x0010".
  val (rStride, rAttrBase, rOne, rMvpBase, rPosBase) = (5, 6, 7, 8, 9)

  val tests = Tests {
    utest.test("borgc-compiled cube.vert renders the correct perspective-correct triangle") {
      run("compiled draw-mode cube.vert") { rig =>
        // Replace DrawRig's own hand-written vertex shader with borgc's.
        rig.rom ++= compiledVert.zipWithIndex.map { case (w, i) => (rig.vsAddr + 4 * i) -> w }

        // cube.vert's UBO, at a fresh address DrawRig's other regions don't use.
        val ubo = 0xC000
        def f32(d: Double): BigInt = BigInt(java.lang.Float.floatToRawIntBits(d.toFloat)) & BigInt(0xFFFFFFFFL)
        def putMat(col0: Seq[Double], col1: Seq[Double], col2: Seq[Double], col3: Seq[Double]): Unit =
          for ((col, c) <- Seq(col0, col1, col2, col3).zipWithIndex; (v, r) <- col.zipWithIndex)
            rig.rom(ubo + 4 * (4 * c + r)) = f32(v)
        // MVP = diag(2, 2, 1, 1), column-major.
        putMat(Seq(2, 0, 0, 0), Seq(0, 2, 0, 0), Seq(0, 0, 1, 0), Seq(0, 0, 0, 1))

        // The clip-space triangle borgc's compiled matmul must reproduce --
        // same shape as BorgDrawTests.perspective(): large, and w varies
        // per vertex, so a wrong perspective-correct interpolation (not
        // just a wrong position) would show up too.
        val want = Seq(at(0, 0, 1.0, r = 1.0, g = 0.0),
                       at(8, 0, 4.0, r = 0.0, g = 1.0),
                       at(0, 8, 2.0, r = 0.0, g = 0.0))
        // position[i] such that MVP * position[i] == want(i): X, Y halved
        // (MVP's 2,2 scale), Z and W unchanged (MVP's identity rows there).
        for ((v, i) <- want.zipWithIndex) {
          rig.rom(ubo + 4 * (16 + 4 * i + 0)) = f32(v.x / 2)
          rig.rom(ubo + 4 * (16 + 4 * i + 1)) = f32(v.y / 2)
          rig.rom(ubo + 4 * (16 + 4 * i + 2)) = f32(v.z)
          rig.rom(ubo + 4 * (16 + 4 * i + 3)) = f32(v.w)
          // attr[i] = (r, g, 0, 0): texcoord is a pure passthrough in
          // cube.vert, and DrawRig's fs reads FATTR index 0/1 as red/green.
          rig.rom(ubo + 4 * (160 + 4 * i + 0)) = f32(v.r)
          rig.rom(ubo + 4 * (160 + 4 * i + 1)) = f32(v.g)
          rig.rom(ubo + 4 * (160 + 4 * i + 2)) = f32(0)
          rig.rom(ubo + 4 * (160 + 4 * i + 3)) = f32(0)
        }

        val count = rig.draw(want, extra = Seq(
          BorgGpuRegs.ls_base_offset -> BigInt(ubo),
          (rStride * 4).U -> BigInt(4), (rAttrBase * 4).U -> BigInt(160), (rOne * 4).U -> BigInt(1),
          (rMvpBase * 4).U -> BigInt(0), (rPosBase * 4).U -> BigInt(16),
        ))
        val expect = BorgDrawTests.samplesCovered(want)
        println(s"  $count samples covered (expect $expect)")
        utest.assert(count == expect)

        val (e, _) = planes(want)
        var checked = 0
        for (y <- 0 until BorgDrawTests.Size; x <- 0 until BorgDrawTests.Size
             if BorgDrawTests.offsets.forall { case (ox, oy) => BorgDrawTests.inside(want, x + 0.5 + ox, y + 0.5 + oy) }) {
          val ev = e.map(eval(_, x + 0.5, y + 0.5)); val q = ev.sum
          val (r, g) = (ev(0) / q, ev(1) / q)
          val (hr, hg, hb) = rig.pixel(x, y)
          Predef.assert(math.abs(hr - r * 255) <= 2.0 && math.abs(hg - g * 255) <= 2.0 && hb == 0,
            f"pixel ($x,$y): ($hr,$hg,$hb) vs (${r * 255}%.1f, ${g * 255}%.1f, 0)")
          checked += 1
        }
        println(s"  $checked fully covered pixels match the perspective-correct reference")
        utest.assert(checked >= 8)
        println("  PASSED — borgc's compiled vertex-pulling/matmul/SOUT output is correct on real hardware")
      }
    }

    utest.test("borgc-compiled cube.frag renders correct lit, textured, sRGB-encoded output") {
      run("compiled draw-mode cube.vert + cube.frag", BorgDrawTests.quadCfg) { rig =>
        // Both shaders are borgc's real compiled output this time -- the
        // fragment compile's load_input/FATTR codegen for texcoord AND
        // frag_pos, its in-shader-built (FNEG/FSTEP r30) TEX control word,
        // DDX/DDY, normalize/dot, and the hardware FSRGB instruction all
        // need the SAME cube.vert that actually produces frag_pos (DrawRig's
        // own hand-written vs never writes it, so it can't stand in here).
        rig.rom ++= compiledVert.zipWithIndex.map { case (w, i) => (rig.vsAddr + 4 * i) -> w }
        // borgc's lightDir constants now live in the uniform memory window
        // (u20-u22, draw_fs_const_offset) instead of pinned GPRs -- see
        // mesa/src/borg/compiler/lib.rs's load_const arm and its comment on
        // why (a register-pressured draw-mode VERTEX shader's own general
        // pool could reach a GPR-pinned r17-19 and clobber it before the
        // fragment shader ever ran; a hardware-caught bug). Instructions 0-1
        // (FNEG/FSTEP of r30) are unrelated -- borgc's usual in-shader-built
        // TEX control word, unchanged from before.
        val compiledFrag: Seq[BigInt] = Seq(
          BigInt("0C0F0000",16), BigInt("10000080",16), BigInt("88020500",16), BigInt("08A28000",16), BigInt("00B30104",16), BigInt("10C38004",16),
          BigInt("3C000100",16), BigInt("40000180",16), BigInt("88028500",16), BigInt("08A28000",16), BigInt("00B30404",16), BigInt("40C38004",16),
          BigInt("3C000400",16), BigInt("40000480",16), BigInt("88030500",16), BigInt("08A28000",16), BigInt("00B30684",16), BigInt("68C38004",16),
          BigInt("3C000680",16), BigInt("40000700",16), BigInt("08E10000",16), BigInt("0C000780",16), BigInt("78368004",16), BigInt("08968780",16),
          BigInt("0C078680",16), BigInt("68E40784",16), BigInt("08340680",16), BigInt("0C068400",16), BigInt("40910684",16), BigInt("08000400",16),
          BigInt("40D68484",16), BigInt("48F78404",16), BigInt("34040480",16), BigInt("08978400",16), BigInt("08900780",16), BigInt("08FA9000",16),
          BigInt("08968780",16), BigInt("00FB1484",16), BigInt("488A1784",16), BigInt("10078480",16), BigInt("08978400",16), BigInt("88000500",16),
          BigInt("08A28480",16), BigInt("48B30784",16), BigInt("78C38484",16), BigInt("88008500",16), BigInt("08A28780",16), BigInt("78B30004",16),
          BigInt("00C38784",16), BigInt("0CF48A04",16), BigInt("088A0780",16), BigInt("38078D00",16), BigInt("088A8780",16), BigInt("088B0000",16),
          BigInt("38078D80",16), BigInt("38000E00",16), BigInt("00000000",16),
        )

        def f32(d: Double): BigInt = BigInt(java.lang.Float.floatToRawIntBits(d.toFloat)) & BigInt(0xFFFFFFFFL)

        // cube.vert's UBO (same layout as the vertex-only test): MVP at
        // word 0, position[] at word 16, attr[] (== texcoord here) at word
        // 160. w == 1 and z == 0.5 for every vertex on purpose: with no
        // perspective variation, frag_pos (the raw, un-divided clip xyz)
        // interpolates PLAIN-AFFINE across the triangle -- DDX/DDY of an
        // affine function is a single constant reproduced exactly by ANY
        // two-point finite difference, whatever quad-differencing
        // convention the hardware uses, which sidesteps needing to model
        // that convention in the reference at all.
        val ubo = 0xC000
        def putMat(col0: Seq[Double], col1: Seq[Double], col2: Seq[Double], col3: Seq[Double]): Unit =
          for ((col, c) <- Seq(col0, col1, col2, col3).zipWithIndex; (v, r) <- col.zipWithIndex)
            rig.rom(ubo + 4 * (4 * c + r)) = f32(v)
        putMat(Seq(2, 0, 0, 0), Seq(0, 2, 0, 0), Seq(0, 0, 1, 0), Seq(0, 0, 0, 1))

        val want = Seq(at(0, 0, 1.0), at(8, 0, 1.0), at(0, 8, 1.0))     // w == 1, z == 0.5 for all
        val uv = Seq((0.0, 0.0), (1.0, 0.0), (0.0, 1.0))
        for ((v, i) <- want.zipWithIndex) {
          rig.rom(ubo + 4 * (16 + 4 * i + 0)) = f32(v.x / 2); rig.rom(ubo + 4 * (16 + 4 * i + 1)) = f32(v.y / 2)
          rig.rom(ubo + 4 * (16 + 4 * i + 2)) = f32(v.z);     rig.rom(ubo + 4 * (16 + 4 * i + 3)) = f32(v.w)
          rig.rom(ubo + 4 * (160 + 4 * i + 0)) = f32(uv(i)._1); rig.rom(ubo + 4 * (160 + 4 * i + 1)) = f32(uv(i)._2)
          rig.rom(ubo + 4 * (160 + 4 * i + 2)) = f32(0);        rig.rom(ubo + 4 * (160 + 4 * i + 3)) = f32(0)
        }

        // A flat R8G8B8A8_UNORM texture: any u/v the (untested-here)
        // texcoord interpolation produces samples the same texel, so this
        // test isolates the fragment compile's own new codegen (DDX/DDY,
        // normalize/dot, in-shader TEX control word, FSRGB) rather than
        // re-proving texcoord interpolation, which the vertex test and
        // BorgDrawTests.renderToTexture already cover.
        val texBase = 0xC800
        val (tr, tg, tb) = (200, 100, 50)
        val texWord = BigInt(tr) | (BigInt(tg) << 8) | (BigInt(tb) << 16) | (BigInt(255) << 24)
        for (i <- 0 until 8 * 8 * 4 / 4) rig.rom(texBase + 4 * i) = texWord
        val fm = TexFormat.byName("R8G8B8A8_UNORM")
        val desc = SamplerCtl.descHeader(texBase, 8, 8, 1, 1, fm.code, SamplerCtl.Tiled, SamplerCtl.T2D, 0)
                     .map(BigInt(_)) ++ Seq.fill(12)(BigInt(0))
        for ((w, i) <- desc.zipWithIndex) rig.rom(rig.texDesc + 4 * i) = w
        for ((w, i) <- Seq[BigInt](2 << 3 | 2 << 6, 0, 0, 0).zipWithIndex) rig.rom(rig.sampDesc + 4 * i) = w

        // lightDir(0.424, 0.566, 0.707): u20/u21/u22, i.e. rig.fsConst +
        // 4*(u-20) -- borgc's own "draw-mode uniform consts" log line for
        // this exact compile. draw_fs_const_offset is already pointed at
        // rig.fsConst by DrawRig.draw()'s own default register sequence,
        // so this is a plain rom write, not another `extra` register poke.
        rig.rom(rig.fsConst + 0) = BigInt("3ed91687", 16)
        rig.rom(rig.fsConst + 4) = BigInt("3f10e560", 16)
        rig.rom(rig.fsConst + 8) = BigInt("3f34fdf4", 16)

        val count = rig.draw(want, frag = compiledFrag, extra = Seq(
          BorgGpuRegs.ls_base_offset -> BigInt(ubo),
          (rStride * 4).U -> BigInt(4), (rAttrBase * 4).U -> BigInt(160), (rOne * 4).U -> BigInt(1),
          (rMvpBase * 4).U -> BigInt(0), (rPosBase * 4).U -> BigInt(16),
        ))
        val expect = BorgDrawTests.samplesCovered(want)
        println(s"  $count samples covered (expect $expect)")
        utest.assert(count == expect)

        val (e, _) = planes(want)
        def bary(x: Double, y: Double): Seq[Double] = { val ev = e.map(eval(_, x, y)); val q = ev.sum; ev.map(_ / q) }
        def fragPos(x: Double, y: Double): (Double, Double, Double) = {
          val l = bary(x, y)
          (0 until 3).foldLeft((0.0, 0.0, 0.0)) { case ((ax, ay, az), i) =>
            (ax + l(i) * want(i).x, ay + l(i) * want(i).y, az + l(i) * want(i).z)
          }
        }
        def sub(a: (Double, Double, Double), b: (Double, Double, Double)) = (a._1 - b._1, a._2 - b._2, a._3 - b._3)
        def cross(a: (Double, Double, Double), b: (Double, Double, Double)) =
          (a._2 * b._3 - a._3 * b._2, a._3 * b._1 - a._1 * b._3, a._1 * b._2 - a._2 * b._1)
        def norm(a: (Double, Double, Double)) = math.sqrt(a._1 * a._1 + a._2 * a._2 + a._3 * a._3)
        val lightDir = (0.424, 0.566, 0.707)
        def dot(a: (Double, Double, Double), b: (Double, Double, Double)) = a._1 * b._1 + a._2 * b._2 + a._3 * b._3
        // DDX/DDY are a 2x2-fragment-quad finite difference, not a
        // per-pixel property: a pixel whose own samples are fully covered
        // can still sit next to a quad-mate pixel outside the triangle (or
        // off-screen), whose stale/undefined value corrupts the derivative.
        // Require the whole 3x3 neighbourhood fully covered so the check
        // is safe under either quad-pairing convention.
        def fullyCovered(px: Int, py: Int) =
          px >= 0 && py >= 0 && px < BorgDrawTests.Size && py < BorgDrawTests.Size &&
          BorgDrawTests.offsets.forall { case (ox, oy) => BorgDrawTests.inside(want, px + 0.5 + ox, py + 0.5 + oy) }
        var checked = 0; var worst = 0.0
        for (y <- 0 until BorgDrawTests.Size; x <- 0 until BorgDrawTests.Size
             if (-1 to 1).forall(dy => (-1 to 1).forall(dx => fullyCovered(x + dx, y + dy)))) {
          val (px, py) = (x + 0.5, y + 0.5)
          val dX = sub(fragPos(px + 1, py), fragPos(px, py)); val dY = sub(fragPos(px, py + 1), fragPos(px, py))
          val n0 = cross(dX, dY); val nl = norm(n0)
          val normal = (n0._1 / nl, n0._2 / nl, n0._3 / nl)
          val light = math.max(0.0, dot(lightDir, normal))
          val linear = Seq(tr / 255.0, tg / 255.0, tb / 255.0).map(_ * light)
          val expect8 = linear.map(c => BorgCoreTestHelpers.linearToSrgb(c.toFloat) * 255)
          val (hr, hg, hb) = rig.pixel(x, y)
          val got = Seq(hr, hg, hb)
          val err = got.zip(expect8).map { case (h, e) => math.abs(h - e) }.max
          worst = math.max(worst, err)
          Predef.assert(err <= 8.0,
            f"pixel ($x,$y): light=$light%.4f normal=$normal got=$got expected=(${expect8(0)}%.1f,${expect8(1)}%.1f,${expect8(2)}%.1f)")
          checked += 1
        }
        println(f"  $checked fully covered pixels match the lit/textured/sRGB reference (worst ${worst}%.1f/255)")
        // Lower bar than the vertex-only test's: the 3x3-neighbourhood
        // safety margin this test adds on top of "fully covered" (DDX/DDY
        // need the whole quad, not just this pixel, valid -- see
        // fullyCovered's own comment) shrinks the qualifying interior a lot
        // for this same half-screen triangle; 6 is what it actually yields,
        // not an arbitrarily lowered bar.
        utest.assert(checked >= 6)
        println("  PASSED — borgc's compiled fragment DDX/DDY, normalize, TEX and FSRGB codegen is correct on real hardware")
      }
    }
  }
}
