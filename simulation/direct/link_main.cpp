// link_sim: Borg behind the board's HostLink, on stdin/stdout as direct_sim (BORG_HW_RAW).
// The protocol runs in the RTL; this side only moves bytes and answers the memory port.
#include "direct_sim.h"
#include <deque>

int main() {
  DirectSim sim;
  auto &v = sim.model.view;
  std::deque<uint8_t> in;
  std::vector<uint8_t> out;
  uint8_t buf[1 << 16];
  for (;;) {
    v.in_valid = !in.empty();
    v.in_data = in.empty() ? 0 : in.front();
    v.out_ready = 1;
    sim.model.eval();
    if (in.empty() && v.in_ready) {
      if (!out.empty() && write(1, out.data(), out.size()) != (ssize_t)out.size()) break;
      out.clear();
      ssize_t n = read(0, buf, sizeof buf);
      if (n <= 0) break;
      in.insert(in.end(), buf, buf + n);
      continue;
    }
    const bool took = v.in_valid && v.in_ready, gave = v.out_valid;
    const uint8_t b = v.out_data;
    sim.tick();
    if (took) in.pop_front();
    if (gave) out.push_back(b);
  }
  if (sim.progress) fprintf(stderr, "[direct %d] total %llu cycles\n", (int)getpid(), (unsigned long long)sim.cycles);
  return 0;
}
