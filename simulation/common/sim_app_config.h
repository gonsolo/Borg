// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// sim_app_config.h — per-app configuration for headless Borg simulators.
// Included by verilator/main.cpp and arcilator/main.cpp.

#pragma once
#include <cstdint>
#include <string>

struct AppConfig {
    bool        has_camera; // unused by kernel (borgvk sends MVP via 0xAD packets)
    float       cam_angle_x;
    float       cam_angle_y;
    int         num_frames;
};

inline AppConfig get_app_config(const std::string& app_name) {
    (void)app_name;
    // Thin kernel: all content arrives from borgvk at runtime.
    // The headless runner is only useful with --cts-uart (borgvk-format UART bytes).
    return {
        false,  // no DRAM camera side-channel
        0.0f,
        0.0f,
        1,      // run until first frame complete (or watchdog)
    };
}
