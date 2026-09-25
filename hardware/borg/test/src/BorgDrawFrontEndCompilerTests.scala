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

  /** borgc-cli -s vert -H ... cube_vert.spv, BORGC_DRAW_MODE=1, 72 words. */
  val compiledVert: Seq[BigInt] = Seq(
      BigInt("285F0200",16), BigInt("1C620500",16), BigInt("44050200",16), BigInt("1C750580",16), BigInt("44058500",16), BigInt("1C758600",16),
      BigInt("44060580",16), BigInt("1C760680",16), BigInt("44068600",16), BigInt("84400000",16), BigInt("84A00080",16), BigInt("84B00100",16),
      BigInt("84C00180",16), BigInt("44040600",16), BigInt("1C740680",16), BigInt("44068580",16), BigInt("1C768500",16), BigInt("44050680",16),
      BigInt("1C750200",16), BigInt("44020500",16), BigInt("1C720700",16), BigInt("44070200",16), BigInt("1C770780",16), BigInt("44078700",16),
      BigInt("1C778800",16), BigInt("44080780",16), BigInt("1C780880",16), BigInt("44088800",16), BigInt("1C788900",16), BigInt("44090880",16),
      BigInt("1C790980",16), BigInt("44098900",16), BigInt("1C798A00",16), BigInt("440A0980",16), BigInt("1C7A0A80",16), BigInt("440A8A00",16),
      BigInt("1C7A8B00",16), BigInt("440B0A80",16), BigInt("1C7B0B80",16), BigInt("440B8B00",16), BigInt("1C7B8C00",16), BigInt("440C0B80",16),
      BigInt("1C7C0C80",16), BigInt("440C8C00",16), BigInt("285F0C80",16), BigInt("1C9C8D00",16), BigInt("440D0C80",16), BigInt("1C7D0D80",16),
      BigInt("440D8D00",16), BigInt("1C7D8E00",16), BigInt("440E0D80",16), BigInt("1C7E0E80",16), BigInt("440E8E00",16), BigInt("09CA8E80",16),
      BigInt("09CB0A80",16), BigInt("09CB8B00",16), BigInt("09CC0B80",16), BigInt("E9B88E04",16), BigInt("A9B90E84",16), BigInt("B1B98A84",16),
      BigInt("B9BA0B04",16), BigInt("E1A20B84",16), BigInt("E9A70E04",16), BigInt("A9A78E84",16), BigInt("B1A80A84",16), BigInt("B9960004",16),
      BigInt("E1958084",16), BigInt("E9968104",16), BigInt("A9950184",16), BigInt("84000200",16), BigInt("84100280",16), BigInt("84200300",16),
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
  }
}
