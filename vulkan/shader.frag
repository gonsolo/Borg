#version 450

// Input from the vertex shader (interpolated)
layout(location = 0) in vec3 fragColor;

// Output to the framebuffer
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(fragColor, 1.0);
}
