// Arcilator harness: standalone BorgCore (custom FMA), drive ADD/MUL/FMA/FNEG
// via MMIO and read regReadData.  See docs/arcilator_custom_fma_bug.md.
// Result: all correct in arcilator — BorgCore is fine standalone; only the full
// SoC miscompiles.  Encodings come from `mill asic.tt.runMain asic.tt.BorgCoreArcMain`.
//
// Build (from simulation/arcilator/debug, after the runMain above):
//   firtool ../../../out/hardware/borg/firrtl_corearc/BorgCore.fir --disable-layers=Verification --ir-hw \
//     | arcilator --inline --observe-memories --state-file core_state.json -o core_arc.ll
//   python3 ../arcilator-header-cpp.py core_state.json > core.h
//   llc -O3 -relocation-model=pic --filetype=obj core_arc.ll -o core_arc.o
//   clang++ -O3 core_harness.cpp core_arc.o -I.. -o core_harness && ./core_harness
#include "core.h"
#include <cstdio>
#include <cstdint>

static BorgCore m;
static void step(){ m.view.clock=0; m.eval(); m.view.clock=1; m.eval(); }
static void writeCore(uint16_t addr, uint32_t data){
  m.view.io_bus_address=addr; m.view.io_bus_data_in=data;
  m.view.io_bus_is_writing=1; m.view.io_bus_is_reading=0; step();
  m.view.io_bus_is_writing=0; step();
}
static void writeReg(int r, uint32_t bits){ writeCore(r*4, bits); }
static void writeImem(int slot, uint32_t instr){ writeCore(128+slot*4, instr); }
static void resetCtrl(){ m.view.io_control_reset=1; step(); m.view.io_control_reset=0; step(); }
static uint16_t readReg(int r){
  m.view.io_bus_address=r*4; m.view.io_bus_is_reading=1; m.view.io_bus_is_writing=0; step();
  uint16_t v = m.view.io_regReadData; m.view.io_bus_is_reading=0; return v;
}
static void startAndWait(){
  m.view.io_control_start=1; step(); m.view.io_control_start=0;
  int i=0; for(; i<300 && m.view.io_status_running; i++) step();
  if(i>=300) printf("  [watchdog: never idle]\n");
}

int main(){
  m.view.reset=1; step(); step(); m.view.reset=0; step();
  resetCtrl();
  writeReg(0,0x4000); writeReg(1,0x4200);            // 2.0, 3.0
  writeImem(0,0x100100); writeImem(1,0);             // ADD r2,r0,r1
  resetCtrl(); startAndWait();
  printf("ADD(2,3)   = 0x%04x  (expect 0x4500)\n", readReg(2));

  writeReg(0,0x4200); writeReg(1,0x4400);            // 3.0, 4.0
  writeImem(0,0x8100100); writeImem(1,0);            // MUL
  resetCtrl(); startAndWait();
  printf("MUL(3,4)   = 0x%04x  (expect 0x4A00)\n", readReg(2));

  writeReg(0,0x4000); writeReg(1,0x4200); writeReg(3,0x3C00);  // 2,3,1
  writeImem(0,0x18100104); writeImem(1,0);           // FMA r2,r0,r1,r3
  resetCtrl(); startAndWait();
  printf("FMA(2,3,1) = 0x%04x  (expect 0x4700)\n", readReg(2));

  writeReg(0,0x4000);                                // 2.0
  writeImem(0,0xc000100); writeImem(1,0);            // FNEG r2,r0
  resetCtrl(); startAndWait();
  printf("FNEG(2)    = 0x%04x  (expect 0xC000)\n", readReg(2));
  return 0;
}
