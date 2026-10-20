// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** Generates software/borg/borg_tex_format.h, the texel format codes of a texture descriptor,
  * and software/borg/borg_tex_format.rs, codes and texel sizes for the borg_layout crate, from
  * [[TexFormat.all]].
  *
  *   mill hardware.borg.runMain borg.EmitTexFormatHeader [out-path [rust-out-path]]
  */
object EmitTexFormatHeader extends App {
  private val out =
    if (args.nonEmpty) args(0) else "software/borg/borg_tex_format.h"

  private val body = new StringBuilder
  body ++= "// SPDX-FileCopyrightText: © 2026 Andreas Wendleder\n"
  body ++= "// SPDX-License-Identifier: GPL-3.0-or-later\n"
  body ++= "// GENERATED from hardware/borg/src/TexFormat.scala by\n"
  body ++= "//   mill hardware.borg.runMain borg.EmitTexFormatHeader\n\n"
  body ++= "#pragma once\n\n"
  body ++= "// Texel format codes for a texture descriptor, word 2 [19:14] (docs/B2_texture_unit.md)\n"
  for (fm <- TexFormat.all)
    body ++= f"#define BORG_TEX_FORMAT_${fm.name}%-28s ${fm.code}%d\n"

  java.nio.file.Files.write(java.nio.file.Paths.get(out), body.toString.getBytes)
  println(s"EmitTexFormatHeader: wrote $out (${TexFormat.all.length} formats)")

  private val rsOut = if (args.length > 1) args(1) else "software/borg/borg_tex_format.rs"
  private val rs = new StringBuilder
  rs ++= "// SPDX-FileCopyrightText: © 2026 Andreas Wendleder\n"
  rs ++= "// SPDX-License-Identifier: GPL-3.0-or-later\n"
  rs ++= "// GENERATED from hardware/borg/src/TexFormat.scala by\n"
  rs ++= "//   mill hardware.borg.runMain borg.EmitTexFormatHeader\n\n"
  rs ++= "// Name = code, texel bytes; included by mesa/src/borg/layout/tex_format.rs.\n"
  rs ++= "formats! {\n"
  for (fm <- TexFormat.all)
    rs ++= s"    ${fm.name} = ${fm.code}, ${fm.bytes};\n"
  rs ++= "}\n"
  java.nio.file.Files.write(java.nio.file.Paths.get(rsOut), rs.toString.getBytes)
  println(s"EmitTexFormatHeader: wrote $rsOut")
}
