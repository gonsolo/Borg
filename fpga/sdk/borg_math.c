// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

#include "borg_math.h"

/**
 * Convert FP16 angle (radians) to a LUT index 0..255.
 * Maps [0, 2π) → [0, 256) linearly using integer math.
 *
 * Strategy: decompose the FP16 float manually:
 *   sign(1) | exponent(5) | mantissa(10)
 * Convert to a fixed-point representation, then scale to [0, 256).
 */

// Simple FP16 → unsigned fixed-point (Q8.8 or similar)
// Returns angle * 256 / (2π) as an integer [0, 255]
static uint8_t angle_to_index(uint16_t angle) {
    // Extract FP16 components
    uint16_t sign = (angle >> 15) & 1;
    int16_t  exp  = ((angle >> 10) & 0x1F) - 15;  // unbiased exponent
    uint16_t mant = angle & 0x3FF;

    // Handle zero/denorm
    if ((angle & 0x7FFF) == 0) return 0;
    if (exp < -10) return 0;

    // Reconstruct as fixed-point: value = (1 + mant/1024) * 2^exp
    // We want index = value * 256 / (2π) ≈ value * 40.74
    // Use integer approximation: value * 2611 / 64  (2611/64 ≈ 40.80)
    uint32_t fixed = (1024 + mant);  // 1.mant in Q10
    if (exp >= 0) {
        fixed <<= exp;
    } else {
        fixed >>= (-exp);
    }
    // fixed is now value * 1024
    // index = fixed * 256 / (2π * 1024) = fixed * 256 / 6434
    //       = fixed / 25.13 ≈ fixed * 41 / 1024
    uint32_t idx = (fixed * 41) >> 10;

    // Handle negative angles: wrap to [0, 256)
    if (sign) {
        idx = 256 - (idx & 0xFF);
    }
    return (uint8_t)(idx & 0xFF);
}

uint16_t fp16_sin(uint16_t angle_fp16) {
    uint8_t idx = angle_to_index(angle_fp16);

    // Quadrant reduction: idx is [0, 255] mapping to [0, 2π)
    // Q0: [0,  63]  → sin = +lut[idx]
    // Q1: [64, 127] → sin = +lut[128 - idx]
    // Q2: [128,191] → sin = -lut[idx - 128]
    // Q3: [192,255] → sin = -lut[256 - idx]
    uint8_t quadrant = idx >> 6;
    uint8_t lut_idx;
    uint16_t negate = 0;

    switch (quadrant) {
        case 0: lut_idx = idx;           break;
        case 1: lut_idx = 128 - idx;     break;
        case 2: lut_idx = idx - 128;     negate = 0x8000; break;
        case 3: lut_idx = 256 - idx;     negate = 0x8000; break;
        default: lut_idx = 0; break;
    }

    if (lut_idx > 64) lut_idx = 64;  // clamp

    return sin_lut[lut_idx] ^ negate;
}

uint16_t fp16_cos(uint16_t angle_fp16) {
    // cos(x) = sin(x + π/2)
    // In index space: shift by 64 (= π/2 worth of indices)
    uint8_t idx = angle_to_index(angle_fp16);
    idx += 64;  // wraps naturally for uint8_t

    uint8_t quadrant = idx >> 6;
    uint8_t lut_idx;
    uint16_t negate = 0;

    switch (quadrant) {
        case 0: lut_idx = idx;           break;
        case 1: lut_idx = 128 - idx;     break;
        case 2: lut_idx = idx - 128;     negate = 0x8000; break;
        case 3: lut_idx = 256 - idx;     negate = 0x8000; break;
        default: lut_idx = 0; break;
    }

    if (lut_idx > 64) lut_idx = 64;

    return sin_lut[lut_idx] ^ negate;
}
