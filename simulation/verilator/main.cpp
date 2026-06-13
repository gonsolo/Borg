#include "BorgSimulator.h"
#include "sim_app_config.h"

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " <firmware.bin> <app_name> [width] [height] [tex_dim]\n";
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::string firmware_path = argv[1];
    std::string app_name = argv[2];

    uint32_t width = argc > 3 ? std::atoi(argv[3]) : 32;
    uint32_t height = argc > 4 ? std::atoi(argv[4]) : 32;
    uint32_t tex_dim = argc > 5 ? std::atoi(argv[5]) : 32;

    VerBorgSimulator sim(firmware_path, width, height);

    AppConfig cfg = get_app_config(app_name);
    sim.load_texture(cfg.tex_path, cfg.tex_dim);
    if (cfg.has_camera)
        sim.set_camera_angles(cfg.cam_angle_x, cfg.cam_angle_y);

    std::cout << "[SIM] Starting simulation...\n";

    uint64_t total_cycles = 0;
    uint64_t max_cycles = argc > 6 ? (uint64_t)std::atoll(argv[6]) : 0;
    while (!sim.step(100000)) {
        total_cycles += 100000;
        if (total_cycles % 1000000 == 0) {
            std::cout << "[SIM] " << (total_cycles / 1000000) << " million cycles\n" << std::flush;
        }
        if (max_cycles && total_cycles >= max_cycles) {
            std::cout << "[SIM] MAX CYCLES REACHED (" << max_cycles << ") — saving partial framebuffer\n";
            sim.save_ppm(app_name);
            sim.report_bandwidth();
            return 1;
        }
    }
    std::cout << "[SIM] Frame complete! DONE_MARKER detected.\n";
    std::cout << "Total Sim Cycles:  " << total_cycles << " cycles.\n";

    sim.save_ppm(app_name);
    sim.report_bandwidth();

    return 0;
}
