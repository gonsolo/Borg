// Arcilator co-sim: BorgFp16Fma vs HardFloat MulAddRecFN, same inputs.
// See docs/arcilator_custom_fma_bug.md. Result: 0 / 400k diverge.
//
// Build (from simulation/arcilator/debug, after `mill asic.tt.runMain asic.tt.FmaArcMain`):
//   firtool ../../../out/hardware/borg/firrtl_fma/FmaArcTop.fir --disable-layers=Verification --ir-hw \
//     | arcilator --inline --observe-memories --state-file fma_state.json -o fma_arc.ll
//   python3 ../arcilator-header-cpp.py fma_state.json > fma.h
//   llc -O3 -relocation-model=pic --filetype=obj fma_arc.ll -o fma_arc.o
//   clang++ -O3 fma_cosim.cpp fma_arc.o -I.. -o fma_cosim && ./fma_cosim
#include "fma.h"
#include <cstdio>
#include <cstdint>
#include <random>

int main() {
  FmaArcTop m;
  m.view.reset = 1; m.view.clock=0; m.eval(); m.view.clock=1; m.eval();
  m.view.reset = 0; m.view.io_pipeEn=0; m.view.clock=0; m.eval(); m.view.clock=1; m.eval();

  // Pulse+hold timing like the real core (pipeEn high 1 cycle, then low).
  auto run = [&](uint16_t a, uint16_t b, uint16_t c, uint8_t neg, int hold)->std::pair<uint16_t,uint16_t>{
    m.view.io_a=a; m.view.io_b=b; m.view.io_c=c; m.view.io_negate=neg; m.view.io_pipeEn=1;
    m.view.clock=0; m.eval(); m.view.clock=1; m.eval();          // capture
    for(int h=0;h<hold;h++){
      m.view.io_a=0x7777; m.view.io_b=0x1234; m.view.io_c=0x4321; m.view.io_pipeEn=0;
      m.view.clock=0; m.eval(); m.view.clock=1; m.eval();        // hold
    }
    return { m.view.io_outCustom, m.view.io_outHf };
  };

  std::mt19937 rng(0x1234);
  auto gen=[&]()->uint16_t{ int s=(rng()&1)<<15; int e=5+(rng()%21); int f=rng()%0x400; return s|(e<<10)|f; };

  for(int hold : {0,1,2,3}) {
    int fails=0; const int N=100000;
    for(int i=0;i<N;i++){
      uint16_t a=gen(),b=gen(),c=gen(); uint8_t neg=rng()&1;
      auto r=run(a,b,c,neg,hold);
      if(r.first!=r.second){
        if(fails<20) printf("[hold=%d] DIVERGE a=%04x b=%04x c=%04x neg=%d : custom=%04x hf=%04x\n",hold,a,b,c,neg,r.first,r.second);
        fails++;
      }
    }
    printf("hold=%d: %d / %d diverge\n", hold, fails, N);
  }
  return 0;
}
