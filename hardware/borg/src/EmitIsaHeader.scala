// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Generates software/borg/borg_isa.h from [[Instructions.all]].
  *
  * borg_isa.h used to be hand-written, which made it a second, drifting
  * definition of the ISA: it silently fell four opcodes behind (DDX, DDY,
  * FRSQ, FSRGB) and that only surfaced when a test tried to validate a real
  * borgc-compiled shader against it. An incomplete mirror is worse than none,
  * because "not in borg_isa.h" then looks like "not a real opcode".
  *
  * This is the same shape as the register path, which already generates both
  * the Chisel block and the C header from one SystemRDL source -- the ISA
  * simply never got the equivalent, even though Instructions.scala carried the
  * C/Python formatters for it (unused) and called itself the single source of
  * truth.
  *
  *   mill hardware.borg.runMain borg.EmitIsaHeader [out-path]
  */
object EmitIsaHeader extends App {
  import Instructions._

  private val out =
    if (args.nonEmpty) args(0) else "software/borg/borg_isa.h"

  private def base(f7: Int): String = f"0x${f7.toLong << 25}%08XU"

  private def macroFor(name: String, f7: Int, shape: Shape): String = {
    val b = base(f7)
    shape match {
      case RType =>
        s"#define BORG_INSTR_$name(rd, rs1, rs2, funct3) ($b | $C_ARGS_R)"
      case R1Type =>
        s"#define BORG_INSTR_$name(rd, rs1, funct3) ($b | $C_ARGS_FNEG)"
      case R4Type =>
        s"#define BORG_INSTR_$name(rd, rs1, rs2, rs3, funct3) ($b | $C_ARGS_R4)"
      case Store =>
        // No destination: rd carries no meaning, so it is not an argument.
        s"#define BORG_INSTR_$name(rs1, rs2, funct3) ($b | " +
          s"((funct3) << ${BF_FUNCT3.lo}) | ((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}))"
      case Branch =>
        // The target is packed into the unused rs2/rd fields as
        // (target >> 5) and (target & 31); it is not a register.
        s"#define BORG_INSTR_$name(rs1, target, funct3) ($b | " +
          s"((funct3) << ${BF_FUNCT3.lo}) | ((((uint32_t)(target) >> 5) & 0x1FU) << ${BF_RS2.lo}) | " +
          s"((rs1) << ${BF_RS1.lo}) | (((uint32_t)(target) & 0x1FU) << ${BF_RD.lo}))"
      case Mask1 =>
        s"#define BORG_INSTR_$name(rs1, funct3) ($b | " +
          s"((funct3) << ${BF_FUNCT3.lo}) | ((rs1) << ${BF_RS1.lo}))"
      case Mask0 =>
        s"#define BORG_INSTR_$name(funct3) ($b | ((funct3) << ${BF_FUNCT3.lo}))"
      case MaskDest =>
        s"#define BORG_INSTR_$name(rd, funct3) ($b | " +
          s"((funct3) << ${BF_FUNCT3.lo}) | ((rd) << ${BF_RD.lo}))"
    }
  }

  private val body = new StringBuilder
  body ++= "// SPDX-FileCopyrightText: © 2026 Andreas Wendleder\n"
  body ++= "// SPDX-License-Identifier: GPL-3.0-or-later\n//\n"
  body ++= "// GENERATED from hardware/borg/src/Instructions.scala by\n"
  body ++= "//   mill hardware.borg.runMain borg.EmitIsaHeader\n"
  body ++= "// Do not edit: edit Instructions.scala's `all` table instead.\n//\n"
  body ++= "// Borg SPIR-B ISA — instruction encoding macros. The funct7 sub-op\n"
  body ++= "// occupies bits 31:25, so each base below is funct7 << 25.\n\n"
  body ++= "#pragma once\n#include <stdint.h>\n\n"

  // R4-type ops are discriminated by opcode bit 2 plus a 2-bit funct2 at
  // instr[26:25], not by funct7, so neither is in the funct7 table.
  private def r4Base(funct2: Int): String = f"0x${OPCODE_FMA.toLong | (funct2.toLong << 25)}%08XU"
  body ++= s"// R4-type: FMADD (opcode bit ${BITS_OPCODE_FMA_BIT}, funct2=$FUNCT2_FMADD)\n"
  body ++= s"#define BORG_INSTR_FMADD(rd, rs1, rs2, rs3, funct3) " +
           s"(${r4Base(FUNCT2_FMADD)} | $C_ARGS_R4)\n\n"
  // FTEX rd, rs1(U), rs2(V), rs3: rd=texR, rd+1=texG, rd+2=texB. rs3 is a
  // REGISTER INDEX (like FMA's own rs3) whose low bits select which
  // texture-binding slot to sample -- write the desired slot number into a
  // register first, same as any other pinned constant, then pass its index.
  body ++= s"// R4-type: FTEX (opcode bit ${BITS_OPCODE_FMA_BIT}, funct2=$FUNCT2_FTEX)\n"
  body ++= s"#define BORG_INSTR_FTEX(rd, rs1, rs2, rs3, funct3) " +
           s"(${r4Base(FUNCT2_FTEX)} | " +
           s"((funct3) << ${BF_FUNCT3.lo}) | ((rs3) << ${BF_RS3.lo}) | " +
           s"((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo}))\n\n"

  for ((name, f7, shape) <- all)
    body ++= macroFor(name, f7, shape) + "\n"

  body ++= "\n// Special: HALT (an all-zero instruction word)\n"
  body ++= "#define BORG_INSTR_HALT                           0x00000000U\n"

  java.nio.file.Files.write(java.nio.file.Paths.get(out), body.toString.getBytes)
  println(s"EmitIsaHeader: wrote $out (${all.length} opcodes + FMADD + FTEX)")
}
