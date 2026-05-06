#version 450

// Fragment shader: barycentric interpolation + texture sample + lighting modulate.
// Step 34.1: adds texture() call so the SPIR-V compiler (Step 34.2) can emit FTEX.
//
// Data flow:
//   1. Compute barycentric weights w0/w1/w2 from edge values.
//   2. Interpolate UV coordinates.
//   3. Sample texture at (u, v)  →  FTEX instruction (Step 34.2/34.4).
//   4. Interpolate vertex color (R/G/B = per-face lighting factor).
//   5. Modulate: outR = texel.r * color.r  etc.
//   6. Interpolate Z (unchanged).
//
// outU / outV are removed: texture sampling is now inside the shader,
// so the hardware no longer needs to receive UV as separate outputs.

// Texture sampler (set=0, binding=1 — separate from the Params UBO at binding=0)
layout(set = 0, binding = 1) uniform sampler2D tex;

// Per-pixel inputs (edge values from rasterizer)
layout(location = 0) in float e0;
layout(location = 1) in float e1;
layout(location = 2) in float e2;

// Per-triangle uniforms
layout(binding = 0) uniform Params {
    float inv_area;
    float r0, r1, r2;   // vertex color R (= per-face lighting factor)
    float g0, g1, g2;   // vertex color G
    float b0, b1, b2;   // vertex color B
    float z0, z1, z2;
    float u0, u1, u2;
    float v0, v1, v2;
};

// Outputs (4 channels: R, G, B, Z — outU/outV removed)
layout(location = 0) out float outR;
layout(location = 1) out float outG;
layout(location = 2) out float outB;
layout(location = 3) out float outZ;

void main() {
    float w0 = e0 * inv_area;
    float w1 = e1 * inv_area;
    float w2 = e2 * inv_area;

    // Interpolate UV coordinates
    float u = fma(w2, u2, fma(w1, u1, w0 * u0));
    float v = fma(w2, v2, fma(w1, v1, w0 * v0));

    // Sample texture — emits OpImageSampleImplicitLod in SPIR-V (→ FTEX in Step 34.2)
    vec4 texel = texture(tex, vec2(u, v));

    // Interpolate vertex color (lighting factor, same value for all 3 vertices of a face)
    float cr = fma(w2, r2, fma(w1, r1, w0 * r0));
    float cg = fma(w2, g2, fma(w1, g1, w0 * g0));
    float cb = fma(w2, b2, fma(w1, b1, w0 * b0));

    // Modulate: texel color × lighting factor
    outR = texel.r * cr;
    outG = texel.g * cg;
    outB = texel.b * cb;

    // Interpolate Z
    outZ = fma(w2, z2, fma(w1, z1, w0 * z0));
}
