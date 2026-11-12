// link_sim: the board's data path (HostLink, GpuMemMig) on stdin/stdout as direct_sim (BORG_HW_RAW).
// The protocol runs in the RTL; this side moves bytes and plays the MIG on a flat memory.
#include "direct_sim.h"
#include <cstring>
#include <deque>
#include <utility>

int main() {
  DirectSim sim;
  auto &v = sim.model.view;
  std::deque<uint8_t> in;
  std::vector<uint8_t> out;
  std::deque<std::pair<uint64_t, Bytes<16>>> reads;   // due cycle, line
  std::deque<std::pair<Bytes<16>, uint16_t>> wdf;     // data, mask
  const uint32_t latency = 12;                        // a DDR3 read in core cycles, about
  uint8_t buf[1 << 16];
  v.mig_app_rdy = 1; v.mig_app_wdf_rdy = 1; v.mig_init_calib_complete = 1;
  for (;;) {
    v.in_valid = !in.empty();
    v.in_data = in.empty() ? 0 : in.front();
    v.out_ready = 1;
    const bool due = !reads.empty() && reads.front().first <= sim.cycles;
    v.mig_app_rd_data_valid = due;
    if (due) v.mig_app_rd_data = reads.front().second;
    sim.model.eval();
    if (in.empty() && v.in_ready) {
      if (!out.empty() && write(1, out.data(), out.size()) != (ssize_t)out.size()) break;
      out.clear();
      ssize_t n = read(0, buf, sizeof buf);
      if (n <= 0) break;
      in.insert(in.end(), buf, buf + n);
      continue;
    }
    if (due) reads.pop_front();
    if (v.mig_app_wdf_wren) wdf.emplace_back(v.mig_app_wdf_data, v.mig_app_wdf_mask);
    if (v.mig_app_en) {
      uint8_t *line = sim.mem + ((uint64_t(v.mig_app_addr) << 1) & (DirectSim::MEM_BYTES - 1));
      if (v.mig_app_cmd == 0) {
        auto [d, mask] = wdf.front();
        wdf.pop_front();
        for (int b = 0; b < 16; b++) if (!(mask >> b & 1)) line[b] = d.byte[b];
      } else {
        Bytes<16> d;
        memcpy(d.byte, line, 16);
        reads.emplace_back(sim.cycles + latency, d);
      }
    }
    const bool took = v.in_valid && v.in_ready, gave = v.out_valid;
    const uint8_t b = v.out_data;
    sim.edge();
    if (took) in.pop_front();
    if (gave) out.push_back(b);
  }
  if (sim.progress) fprintf(stderr, "[direct %d] total %llu cycles\n", (int)getpid(), (unsigned long long)sim.cycles);
  return 0;
}
