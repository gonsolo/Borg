#version 450

// Fragment shader: batched barycentric interpolation (6 channels).
// Step 7: eliminates per-channel borg_run() calls — one run for all channels.

// Per-pixel inputs (edge values from rasterizer)
layout(location = 0) in float e0;
layout(location = 1) in float e1;
layout(location = 2) in float e2;

// Per-triangle uniforms
layout(binding = 0) uniform Params {
    float inv_area;
    float r0, r1, r2;
    float g0, g1, g2;
    float b0, b1, b2;
    float z0, z1, z2;
    float u0, u1, u2;
    float v0, v1, v2;
};

// Outputs (all 6 interpolated channels)
layout(location = 0) out float outR;
layout(location = 1) out float outG;
layout(location = 2) out float outB;
layout(location = 3) out float outZ;
layout(location = 4) out float outU;
layout(location = 5) out float outV;

void main() {
    float w0 = e0 * inv_area;
    float w1 = e1 * inv_area;
    float w2 = e2 * inv_area;

    outR = fma(w2, r2, fma(w1, r1, w0 * r0));
    outG = fma(w2, g2, fma(w1, g1, w0 * g0));
    outB = fma(w2, b2, fma(w1, b1, w0 * b0));
    outZ = fma(w2, z2, fma(w1, z1, w0 * z0));
    outU = fma(w2, u2, fma(w1, u1, w0 * u0));
    outV = fma(w2, v2, fma(w1, v1, w0 * v0));
}
