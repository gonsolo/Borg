# SPDX-FileCopyrightText: (c) 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

# FP16 (IEEE 754 half-precision) conversion utilities.


def fp16_to_float(bits):
    """Convert FP16 bits (uint16) to Python float."""
    sign = (bits >> 15) & 1
    exp = (bits >> 10) & 0x1F
    frac = bits & 0x3FF
    if exp == 0:
        val = (frac / 1024.0) * (2 ** -14)
    elif exp == 31:
        val = float('inf') if frac == 0 else float('nan')
    else:
        val = (1.0 + frac / 1024.0) * (2 ** (exp - 15))
    return -val if sign else val


def float_to_fp16(f):
    """Convert Python float to FP16 bits (uint16)."""
    if f == 0.0:
        return 0x0000
    sign = 0
    if f < 0:
        sign = 1
        f = -f
    if f >= 65504.0:
        return (sign << 15) | 0x7C00
    if f < 2.0**-24:
        return sign << 15
    if f < 2.0**-14:
        return (sign << 15) | int(f / 2.0**-14 * 1024 + 0.5)
    exp = 0
    tmp = f
    while tmp >= 2.0:
        tmp /= 2.0
        exp += 1
    while tmp < 1.0:
        tmp *= 2.0
        exp -= 1
    frac_bits = int((tmp - 1.0) * 1024 + 0.5)
    biased = exp + 15
    if frac_bits >= 1024:
        frac_bits = 0
        biased += 1
    if biased >= 31:
        return (sign << 15) | 0x7C00
    return (sign << 15) | (biased << 10) | frac_bits
