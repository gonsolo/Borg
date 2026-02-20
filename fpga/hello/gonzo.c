#include <csr.h>
#include <uart.h>
#include "test_data.h"
#define printf uart_printf

// --- BORG HARDWARE DEFINITIONS ---
// Peripheral 39 base address
// 0x08000000 (User Window) + 0x5C0 (Borg Offset)
#define BORG_BASE (0x080005C0)

#define BORG_ADDR_A       (BORG_BASE + 0)
#define BORG_ADDR_B       (BORG_BASE + 4)
#define BORG_ADDR_RES     (BORG_BASE + 8)
#define BORG_ADDR_INSTR   (BORG_BASE + 60)

// Macros to perform the actual memory access
#define REG_WRITE(addr, val) (*(volatile unsigned int *)(addr) = (val))
#define REG_READ(addr)        (*(volatile unsigned int *)(addr))

typedef union {
    float f;
    unsigned int i;
} float_bits;

int run_test_case(float a, float b) {
    float_bits fa, fb, fres;
    fa.f = a;
    fb.f = b;

    // 1. Hardware Transaction
    REG_WRITE(BORG_ADDR_A, fa.i);
    REG_WRITE(BORG_ADDR_B, fb.i);
    unsigned int instr_add = (0x00 << 25) | (1 << 20) | (0 << 15);
    REG_WRITE(BORG_ADDR_INSTR, instr_add);
    fres.i = REG_READ(BORG_ADDR_RES);

    // 2. Software Reference
    float expected = a + b;
    float diff = fres.f - expected;
    if (diff < 0) {
        diff = -diff;
    }

    // 3. Formatting
    int aw = (int)a, af = (int)(a * 100.0f) % 100;
    int bw = (int)b, bf = (int)(b * 100.0f) % 100;
    int rw = (int)fres.f, rf = (int)(fres.f * 100.0f) % 100;
    int ew = (int)expected, ef = (int)(expected * 100.0f) % 100;

    // Fixed indentation for -Werror
    if (af < 0) { af = -af; }
    if (bf < 0) { bf = -bf; }
    if (rf < 0) { rf = -rf; }
    if (ef < 0) { ef = -ef; }

    printf("%d] Check: %4d.%02d + %4d.%02d -> Actual: %4d.%02d (Exp: %4d.%02d)\n",
           read_cycle(), aw, af, bw, bf, rw, rf, ew, ef);

    return (diff < 0.0001f);
}

int main() {
    printf("--- Starting Programmable Adder Batch ---\n");
    
    int all_passed = 1;
    for (int i = 0; i < NUM_TESTS; i++) {
        if (!run_test_case(test_pairs[i][0], test_pairs[i][1])) {
            all_passed = 0;
        }
    }

    if (all_passed) {
        printf("--- All Tests Passed ---\n");
        printf("%d, \033[32mSUCCESS\033[0m] TinyQV Borg Test Finished\n", read_cycle());
    } else {
        printf("--- TESTS FAILED ---\n");
        printf("%d, \033[31mFAILURE\033[0m] TinyQV Borg Test Finished\n", read_cycle());
    }

    return 0;
}
