#include "BorgSimulator.h"

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " <firmware.bin> <app_name>\n";
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::string firmware_path = argv[1];
    std::string app_name = argv[2];

    BorgSimulator sim(firmware_path, 32, 32);

    std::string tex_path = app_name == "vkcube" ? "../../software/borg/borg_texture.dat" : "../../software/borg/test_texture.dat";
    sim.load_texture(tex_path);

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
