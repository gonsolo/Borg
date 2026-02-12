#include <stdint.h>

inline static int mul32x16(int a, int b) {
    int r;
    asm ("mul16 %[r], %[a], %[b]" :
         [r] "=r" (r) :
         [a] "r" (a), [b] "r" (b) :
         );
    return r;
}
int mul32x32(int a, int b);
