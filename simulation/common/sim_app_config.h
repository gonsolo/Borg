// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// sim_app_config.h — shared per-app configuration for Borg simulators.
// Included by both verilator/main.cpp and arcilator/main.cpp so that
// camera angles, texture paths, and dimensions are defined in one place.

#pragma once
#include <string>

struct AppConfig {
    std::string tex_path;
    uint32_t    tex_dim;
    bool        has_camera;
    float       cam_angle_x;
    float       cam_angle_y;
    int         num_frames;  // frames to simulate before saving PPM
};

inline AppConfig get_app_config(const std::string& app_name) {
    if (app_name == "vkcube") {
        return {
            "../../software/borg/borg_texture_small.dat",
            64,
            true,
            0.5236f,   // 30° X
            0.7854f,   // 45° Y — lighting contrast
            2,         // frame 1 = startup, frame 2 = steady state
        };
    } else {
        // triangle renders exactly one frame then enters a UART dump loop,
        // so NUM_FRAMES=1 is both correct and necessary to avoid a hang.
        return {
            "../../software/borg/test_texture.dat",
            32,
            false,
            0.0f,
            0.0f,
            1,
        };
    }
}
