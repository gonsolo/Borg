#include "BorgHdmiSimulator.h"
#include "sim_app_config.h"

// Full-SoC HDMI sim: boot firmware, render a frame, then capture exactly what
// the monitor would show (through the scanout) into <app>_hdmi.ppm.
int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0]
                  << " <firmware.bin> <app_name> [width] [height] [tex_dim] [max_render_cycles]\n";
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::string firmware_path = argv[1];
    std::string app_name = argv[2];
    uint32_t width   = argc > 3 ? std::atoi(argv[3]) : 128;
    uint32_t height  = argc > 4 ? std::atoi(argv[4]) : 128;
    uint32_t tex_dim = argc > 5 ? std::atoi(argv[5]) : 64;
    uint64_t max_cy  = argc > 6 ? (uint64_t)std::atoll(argv[6]) : 30000000ULL;

    VerBorgHdmiSimulator sim(firmware_path, width, height);
    AppConfig cfg = get_app_config(app_name);
    sim.load_texture(cfg.tex_path, cfg.tex_dim);
    if (cfg.has_camera) sim.set_camera_angles(cfg.cam_angle_x, cfg.cam_angle_y);

    std::cout << "[HDMI-SIM] booting + rendering one frame...\n";
    if (!sim.render_one_frame(max_cy)) {
        std::cout << "[HDMI-SIM] no DONE_MARKER within " << max_cy
                  << " cycles — capturing whatever the scanout shows anyway\n";
    } else {
        std::cout << "[HDMI-SIM] frame rendered; capturing display...\n";
    }
    sim.capture_display(app_name);
    return 0;
}
