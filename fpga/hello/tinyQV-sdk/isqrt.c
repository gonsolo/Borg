#include "isqrt.h"
#include "mul.h"

uint32_t isqrt(uint32_t n) {
    if (n == 0) return 0;
    if (n < 4) return 1;
    
    uint32_t lo = 1;
    uint32_t ns = n;
    while (ns >>= 2) {
        lo <<= 1;
    }

    uint32_t hi = (lo << 1) - 1;
    uint32_t mid = (lo + hi) >> 1;

    while (lo <= hi) {
        uint32_t midsq = mul32x16(mid, mid);
        if (midsq == n) return mid;
        if (midsq < n) {
            lo = mid + 1;
        }
        else {
            hi = mid - 1;
        }
        mid = (lo + hi) >> 1;
    }

    if (mul32x16(hi, hi) > n) return hi - 1;
    else return hi;
}

uint32_t isqrt64(uint64_t n) {
    if (n == 0) return 0;
    if (n < 4) return 1;
    
    uint64_t lo = 1;
    uint64_t ns = n;
    while (ns >>= 2) {
        lo <<= 1;
    }

    uint64_t hi = (lo << 1) - 1;
    uint64_t mid = (lo + hi) >> 1;

    while (lo <= hi) {
        // Could be optimized with a dedicated 32x32 -> 64 mul routine
        uint64_t midsq = mid*mid;
        if (midsq == n) return mid;
        if (midsq < n) {
            lo = mid + 1;
        }
        else {
            hi = mid - 1;
        }
        mid = (lo + hi) >> 1;
    }

    if (hi*hi > n) return hi - 1;
    else return hi;
}
