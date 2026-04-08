// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Borg SPIR-B ISA — instruction encoding macros.
//
// These define the 32-bit RISC-V R-type and R4-type instruction encodings
// used by the Borg FPU shader pipeline.  They are ISA definitions and do
// not belong in SystemRDL register descriptions.

#pragma once

// --- R-type: FADD, FMUL ---
#define BORG_INSTR_FADD(rd, rs1, rs2, funct3)    (0x00000000UL | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FMUL(rd, rs1, rs2, funct3)    (0x08000000UL | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))

// --- R4-type: FMADD ---
#define BORG_INSTR_FMADD(rd, rs1, rs2, rs3, funct3) (0x00000004UL | ((funct3) << 12) | ((rs3) << 27) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))

// --- R1-type (unary): FNEG, FSTEP, FRCP ---
#define BORG_INSTR_FNEG(rd, rs1, funct3)         (0x0C000000UL | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FSTEP(rd, rs1, funct3)        (0x10000000UL | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FRCP(rd, rs1, funct3)         (0x14000000UL | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))

// --- Special: HALT ---
#define BORG_INSTR_HALT                           0x00000000UL

