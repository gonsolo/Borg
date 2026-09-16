// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Push-constant end-to-end test shader (Step 50 item 13).
//
// Modelled on the shape the Vulkan CTS's push-constant tests use -- a value
// read from a push-constant block at an explicit offset and written straight
// to the colour output -- but written standalone rather than lifted from
// them: the CTS's shaders are generated in C++, parameterised across range
// sizes and index types, and pull in tessellation stages and multi-range
// layouts that Borg has no path for.
//
// Deliberately branch-free. borgc predicates `if` but REFUSES loops
// (collect_cf_marks pushes "loop" onto its unsupported list), and the
// upstream glslang sample for this feature (spv.pushConstant.vert) is built
// around a `switch` -- which is exactly what not to copy here.
//
// offset = 32 is word index 8, chosen to match the window the two existing
// push-constant tests already pin: the firmware staging test's second packet
// and borgvk_serial_test's well-formed case both use word 8. Keeping all
// three on the same offset means a mistake in the offset arithmetic shows up
// in all of them rather than hiding in whichever one is not run.
//
// No texture, no derivatives, no varyings consumed: the whole point is that
// the output depends on NOTHING except the pushed value, so a render that
// produces the pushed colour proves the LOAD reached the staged bytes.
#version 400
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

layout(push_constant) uniform Material {
    layout(offset = 32) vec4 color;
} matInst;

layout (location = 0) out vec4 uFragColor;

void main() {
    uFragColor = matInst.color;
}
