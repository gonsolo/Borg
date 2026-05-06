#include "BorgSimulator.h"

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

    std::string tex_path = (app_name == "vkcube") ? "../../software/borg/borg_texture_small.dat" : "../../software/borg/test_texture.dat";
    sim.load_texture(tex_path, tex_dim);

    if (app_name == "vkcube") {
        sim.set_camera_angles(0.5236f, 0.7854f);  // 30° X, +45° Y — lighting contrast
    }

    std::cout << "[SIM] Starting simulation...\n";

    uint64_t total_cycles = 0;
    while (!sim.step(100000)) {
        total_cycles += 100000;
        if (total_cycles % 1000000 == 0) {
            std::cout << "[SIM] " << (total_cycles / 1000000) << " million cycles\n";
        }
    }
    std::cout << "[SIM] Frame complete! DONE_MARKER detected.\n";
    std::cout << "Total Sim Cycles:  " << total_cycles << " cycles.\n";

    sim.save_ppm(app_name);

    return 0;
}
