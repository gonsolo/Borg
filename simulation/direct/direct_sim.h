// Direct simulator core: the arcilated Borg module plus the host-side models
// around it -- a register-bus master and a flat DRAM behind the GPU memory port.
// No CPU, no firmware: whoever calls mmio_write()/mmio_read() is the driver.
#pragma once
#include "arc.h"
#include <cstdint>
#include <cstdio>
#include <vector>
#include <cstdlib>
#include <sys/mman.h>

class DirectSim {
public:
  // GPU addresses are DRAM SPI-relative byte addresses; the memory controller
  // on the SoC decodes only the low 24 bits (16 MiB), so does this model.
  static constexpr uint32_t MEM_BYTES = 1u << 25;

  BorgDirectSimTop model;
  uint8_t *mem;   // MEM_BYTES; the process's own, or a mapping shared with the driver (--serve)
  uint64_t cycles = 0;
  uint32_t trace_lo = 0, trace_hi = 0;   // DIRECT_TRACE=lo:hi (hex): log the GPU's reads in [lo, hi)

  // Latencies, in cycles, between a GPU request and its `ready` pulse.  The SoC's
  // controller takes about this long per word through the SDRAM backend.
  uint32_t read_latency  = 6;
  uint32_t write_latency = 3;

  explicit DirectSim(int shared_fd = -1) {
    if (shared_fd >= 0) {
      mem = (uint8_t *)mmap(nullptr, MEM_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, shared_fd, 0);
      if (mem == MAP_FAILED) { perror("[direct] mmap"); exit(1); }
    } else {
      mem = (uint8_t *)calloc(MEM_BYTES, 1);
    }
    auto &v = model.view;
    v.clk = 0; v.rst_n = 0;
    v.mmio_req_valid = 0; v.mmio_resp_ready = 1;
    v.gpu_ready = 0; v.gpu_waccept = 0; v.gpu_data = 0;
    model.eval();
    for (int i = 0; i < 8; i++) edge();
    v.rst_n = 1;
    for (int i = 0; i < 4; i++) tick();
    if (const char *t = getenv("DIRECT_TRACE")) sscanf(t, "%x:%x", &trace_lo, &trace_hi);
    // DIRECT_LATENCY=read:write (cycles): how long the memory takes; 0:0 is the test harness's.
    if (const char *t = getenv("DIRECT_LATENCY")) sscanf(t, "%u:%u", &read_latency, &write_latency);
  }

  // --- memory (host side) ---
  uint32_t r32(uint32_t a) const {
    a &= MEM_BYTES - 1;
    return (uint32_t)mem[a] | ((uint32_t)mem[a + 1] << 8) |
           ((uint32_t)mem[a + 2] << 16) | ((uint32_t)mem[a + 3] << 24);
  }
  void w32(uint32_t a, uint32_t d) {
    a &= MEM_BYTES - 1;
    mem[a] = d; mem[a + 1] = d >> 8; mem[a + 2] = d >> 16; mem[a + 3] = d >> 24;
  }
  void w16(uint32_t a, uint16_t d) {
    a &= MEM_BYTES - 1;
    mem[a] = d; mem[a + 1] = d >> 8;
  }

  // --- clocking ---
  void edge() {
    auto &v = model.view;
    v.clk = 1; model.eval();
    v.clk = 0; model.eval();
    cycles++;
  }

  // One cycle: let the GPU-port slave look at the settled outputs, set its
  // inputs, then take the clock edge.
  void tick() {
    auto &v = model.view;
    // The outputs are settled: every path here ends in an eval() with the clock low, and
    // whoever changes an input afterwards (mmio()) evaluates before it reads an output.
    v.gpu_ready = 0;
    v.gpu_waccept = 0;
    switch (gstate) {
    case IDLE:
      if (v.gpu_wr) {
        gaddr = v.gpu_addr & (MEM_BYTES - 1);
        w16(gaddr, (uint16_t)v.gpu_wdata);          // first word, as the SoC backend does
        uint32_t n = v.gpu_wlen;
        if (n > 1) { grem = n - 1; gaddr += 2; v.gpu_waccept = 1; gstate = SAMPLE; }
        else       { gdelay = write_latency; gstate = DELAY; }
        gread = false;
      } else if (v.gpu_req) {
        gaddr = v.gpu_addr & (MEM_BYTES - 1);
        gdata = r32(gaddr);
        if (trace_lo <= gaddr && gaddr < trace_hi) fprintf(stderr, "[mem] rd %07x = %08x\n", gaddr, gdata);
        gdelay = read_latency; gread = true; gstate = DELAY;
      }
      break;
    case SAMPLE:  // wdata now holds the word the master advanced to
      w16(gaddr, (uint16_t)v.gpu_wdata);
      gaddr += 2;
      if (--grem > 0) v.gpu_waccept = 1;
      else { gdelay = write_latency; gstate = DELAY; }
      break;
    case DELAY:
      if (gdelay-- == 0) {
        v.gpu_ready = 1;
        if (gread) v.gpu_data = gdata;
        gstate = IDLE;
      }
      break;
    }
    edge();   // the rising-edge eval sees the inputs just set
  }

  // --- register bus ---
  // Single outstanding transaction, like the SoC's peripheral port.
  uint32_t mmio(uint32_t addr, bool write, uint32_t data) {
    auto &v = model.view;
    v.mmio_req_valid = 1; v.mmio_req_addr = addr & 0x3FF; v.mmio_req_write = write;
    v.mmio_req_size = 2; v.mmio_req_data = data; v.mmio_resp_ready = 1;
    uint32_t guard = 0;
    for (;;) {
      model.eval();
      bool fire = v.mmio_req_ready;
      tick();
      if (fire) break;
      if (++guard > 100000) { fprintf(stderr, "[direct] mmio req stuck @%x\n", addr); break; }
    }
    v.mmio_req_valid = 0;
    guard = 0;
    for (;;) {
      model.eval();
      if (v.mmio_resp_valid) { uint32_t d = v.mmio_resp_data; tick(); return d; }
      tick();
      if (++guard > 100000) { fprintf(stderr, "[direct] mmio resp stuck @%x\n", addr); return 0; }
    }
  }
  void mmio_write(uint32_t addr, uint32_t d) { mmio(addr, true, d); }
  uint32_t mmio_read(uint32_t addr) { return mmio(addr, false, 0); }

private:
  enum { IDLE, SAMPLE, DELAY } gstate = IDLE;
  uint32_t gaddr = 0, grem = 0, gdelay = 0, gdata = 0;
  bool gread = false;
};
