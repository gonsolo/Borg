#include "ArcBorgSimulator.h"
#include <iostream>

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " <firmware.bin> <app_name>\n";
        return 1;
    }
    std::string firmware_path = argv[1];
    std::string app_name = argv[2];

    uint32_t width = 32;
    uint32_t height = 32;
    uint32_t tex_dim = (app_name == "vkcube" || app_name == "textest") ? 64 : 32;

    bool fast_mode = true;
    if (argc > 3) {
        fast_mode = (std::string(argv[3]) == "fast");
    }

    ArcBorgSimulator sim(firmware_path, fast_mode, width, height);

    std::string tex_path = app_name == "vkcube" ? "../../software/borg/borg_texture_small.dat" : "../../software/borg/test_texture.dat";
    sim.load_texture(tex_path, tex_dim);

    if (app_name == "vkcube") {
        sim.set_camera_angles(-0.4363f, 0.6109f);
    }

    sim.backend_reset();

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

    // In fast mode, arcilator modifies sim_psram_ext directly. We need to copy it back to C++ model
    int PSRAM_OFFSET = sim.find_memory_offset("sim_psram_ext");
    if (PSRAM_OFFSET >= 0) {
        for (size_t i = 0; i < sim.psram->mem.size(); i++) {
            sim.psram->mem[i] = *(sim.get_storage_ptr() + PSRAM_OFFSET + i);
        }
        std::cout << "[SIM] Copied sim_psram_ext → C++ psram for PPM save.\n";
    }

    sim.save_ppm(app_name);

    return 0;
}
