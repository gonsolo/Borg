// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vertex stage for the push-constant end-to-end test (Step 50 item 13).
//
// The firmware's sequencer expects both stages uploaded, so this exists to
// give pushconst.frag something to run behind. It is cube.vert reduced to
// the parts the sequencer actually requires: the same std140 UBO layout
// (MVP, then the position array indexed by gl_VertexIndex), because that
// layout is what borgc's I/O map keys off -- see the "MVP 0->u8..u23,
// position 0->u0..u2" line in its ISA dump. Changing the block shape here
// would move those uniform slots out from under the firmware.
//
// cube.vert's texcoord/frag_pos outputs are dropped: pushconst.frag consumes
// no varyings, and carrying unused ones would only add DMA traffic and make
// the FRAG_USES_FRAGPOS staging mode ambiguous.
#version 400
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

layout(std140, binding = 0) uniform buf {
        mat4 MVP;
        vec4 position[12*3];
        vec4 attr[12*3];
} ubuf;

void main()
{
   gl_Position = ubuf.MVP * ubuf.position[gl_VertexIndex];
}
