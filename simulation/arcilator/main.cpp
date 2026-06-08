#include "ArcBorgSimulator.h"
#include "sim_app_config.h"
#include <iostream>

int main(int argc, char **argv) {
  if (argc < 3) {
    std::cerr << "Usage: " << argv[0] << " <firmware.bin> <app_name>\n";
    return 1;
  }
  std::string firmware_path = argv[1];
  std::string app_name = argv[2];
  uint32_t width = 32;
  uint32_t height = 32;

  ArcBorgSimulator sim(firmware_path, width, height);

  AppConfig cfg = get_app_config(app_name);
  sim.load_texture(cfg.tex_path, cfg.tex_dim);
  if (cfg.has_camera)
    sim.set_camera_angles(cfg.cam_angle_x, cfg.cam_angle_y);
  std::cout << "[SIM] Starting simulation...\n";

  const int NUM_FRAMES = cfg.num_frames;
  uint64_t total_cycles = 0;
  for (int frame = 0; frame < NUM_FRAMES; frame++) {
    uint64_t frame_start = total_cycles;
    while (!sim.step(100000)) {
      total_cycles += 100000;
      if (total_cycles % 5000000 == 0) {
        std::cout << "[SIM] " << (total_cycles / 1000000) << "M cycles (frame " << (frame+1) << ")\n";
      }
    }
    uint64_t frame_cycles = total_cycles - frame_start;
    std::cout << "[SIM] Frame " << (frame+1) << " done: " << frame_cycles << " cycles"
              << "  (est fps @ 25MHz: " << (25000000.0 / frame_cycles) << ")\n";
  }
  sim.save_ppm(app_name);
  return 0;
}
