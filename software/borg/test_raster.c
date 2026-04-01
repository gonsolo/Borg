#include <stdio.h>
#include <stdlib.h>
#include <assert.h>
#include <math.h>

#include "borg_raster.h"
#include "borg_math.h"
#include "borg_fpu.h"

// Mock hardware MMIO math functions using software emulation
fp16_t borg_fp16_add(fp16_t a, fp16_t b) { return fp16_add(a, b); }

static inline fp16_t sim_fp16_mul(fp16_t a, fp16_t b) {
    if (a == 0 || b == 0) return 0;
    int32_t fa = fp16_to_fixed(a);
    int32_t fb = fp16_to_fixed(b);
    int64_t prod = (int64_t)fa * (int64_t)fb;
    return fixed_to_fp16((int32_t)(prod >> 16));
}

fp16_t borg_fp16_mul(fp16_t a, fp16_t b) { return sim_fp16_mul(a, b); }
fp16_t borg_fp16_rcp(fp16_t x) { return fp16_recip(x); }
fp16_t borg_fp16_sub_raw(fp16_t a, fp16_t b) { return fp16_sub(a, b); }

int fp16_to_uint(fp16_t fp16) { return fp16_to_fixed(fp16) >> 16; }
fp16_t uint_to_fp16(int val) { return fixed_to_fp16(val << 16); }

fp16_t borg_fp16_fmadd(fp16_t a, fp16_t b, fp16_t c) {
    return borg_fp16_add(borg_fp16_mul(a, b), c);
}

// Mocks for unused MMIO functions
void borg_run(uint32_t start_pc) { assert(0 && "Unexpected call to borg_run"); }
void borg_load_spirb_shader(const spirb_shader_t *s) { }
void borg_load_spirb_shader_at(const spirb_shader_t *s, int offset) { }

int main() {
    printf("Running Raster Math Tests...\n");

    // Test a CCW Triangle
    // V0: (28.0, 0.375)
    // V1: (-10.7, 20.64)
    // V2: (20.64, 42.5)
    
    // We just want to test that compute_edge_vectors() produces hardware-ready edges
    // structurally evaluating to exactly the +area (CCW interior definition).
    xy16_t screen_pos[3] = {
        { fp16_from_float(28.0f), fp16_from_float(0.375f) },
        { fp16_from_float(-10.7f), fp16_from_float(20.64f) },
        { fp16_from_float(20.64f), fp16_from_float(42.5f) }
    };
    
    xy16_t edges[3];
    compute_edge_vectors(screen_pos, edges);
    
    // Check invariants
    for (int i = 0; i < 3; i++) {
        int prev = (i == 0) ? 2 : i - 1; // Opposite vertex
        
        fp16_t px = screen_pos[prev].x;
        fp16_t py = screen_pos[prev].y;
        
        fp16_t x0 = screen_pos[i].x;
        fp16_t y0 = screen_pos[i].y;
        
        fp16_t dx  = edges[i].x;
        fp16_t ndy = edges[i].y;
        
        fp16_t dpx = BORG_FP16_SUB(px, x0);
        fp16_t dpy = BORG_FP16_SUB(py, y0);
        
        // e_hw(P) = dx0 * dpy0 + ndy0 * dpx0 
        fp16_t term1 = borg_fp16_mul(dx, dpy);
        fp16_t term2 = borg_fp16_mul(ndy, dpx);
        fp16_t e_val = borg_fp16_add(term1, term2); // e_hw(P)
        
        int32_t e_fixed = fp16_to_fixed(e_val);
        float e_float = e_fixed / 65536.0f;
        
        printf("Edge %d Evaluation at Opposite Vertex (V%d): %.2f (FP16: %04X)\n", i, prev, e_float, e_val);
        
        // Assert interior point is strictly positive (otherwise the neg_dy subtraction order is reversed!)
        assert(e_float > 0.0f);
        
        // Check absolute area determinant boundary
        assert(e_float > 1400.0f && e_float < 1550.0f);
    }
    
    printf("SUCCESS! Mathematical Edge Vectors perfectly align with Hardware Iterator requirements.\n");
    return 0;
}
