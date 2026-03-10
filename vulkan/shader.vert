#version 450

// Input attributes from the C program
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec3 inColor;

// Output to the fragment shader (will be interpolated)
layout(location = 0) out vec3 fragColor;

// Uniform variable for the rotation angle (in radians)
layout(push_constant) uniform PushConstants {
    float angle;
} pc;

void main() {
    // 1. Create the 2D rotation matrix
    float s = sin(pc.angle);
    float c = cos(pc.angle);
    mat2 rot = mat2(
        c,  s,  // Column 1
       -s,  c   // Column 2
    );

    // 2. Apply rotation to the position
    vec2 rotatedPos = rot * inPos;

    // 3. Set the final vertex position
    gl_Position = vec4(rotatedPos, 0.0, 1.0);

    // 4. Pass the color to the next stage
    fragColor = inColor;
}
