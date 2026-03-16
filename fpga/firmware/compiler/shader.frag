#version 450

// Fragment shader: barycentric color interpolation
//
// Given edge function values (e0, e1, e2) and an inverse area,
// computes barycentric weights and interpolates vertex colors.
//
// Note: uses fma() to emit fused multiply-add instructions,
// keeping total instruction count within the 7-slot Borg IMEM.

// Per-pixel inputs (edge values from rasterizer)
layout(location = 0) in float e0;
layout(location = 1) in float e1;
layout(location = 2) in float e2;

// Per-frame uniforms
layout(binding = 0) uniform Params {
    float inv_area;
    float c0;
    float c1;
    float c2;
};

// Output
layout(location = 0) out float outColor;

void main() {
    float w0 = e0 * inv_area;
    float w1 = e1 * inv_area;
    float w2 = e2 * inv_area;
    float acc = w0 * c0;
    acc = fma(w1, c1, acc);
    outColor = fma(w2, c2, acc);
}
