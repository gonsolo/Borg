#version 450

// Input attributes from the C program
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inColor;

// Output to the fragment shader (will be interpolated)
layout(location = 0) out vec3 fragColor;

// Uniform variable for the orthogonal routing projection matrix
layout(push_constant) uniform PushConstants {
    mat4 mvp;
} pc;

void main() {
    // Phase 2: Hardware matrix multiplication natively!
    gl_Position = pc.mvp * vec4(inPos, 1.0);

    // Pass the color to the next stage
    fragColor = inColor;
}
