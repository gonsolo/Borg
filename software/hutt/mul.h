#include <stdint.h>

int mul32x32(int a, int b);

// Software multiply - calls the shift-and-add multiply in mul.s
// Previously used a custom TinyQV mul16 hardware instruction.
// Now that we use a standard toolchain, use the regular multiply function.
inline static int mul32x16(int a, int b) {
    return mul32x32(a, b);
}
