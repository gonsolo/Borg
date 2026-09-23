// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
//
// GENERATED from hardware/borg/src/Instructions.scala by
//   mill hardware.borg.runMain borg.EmitIsaHeader
// Do not edit: edit Instructions.scala's `all` table instead.
//
// Borg SPIR-B ISA — instruction encoding macros. The funct7 sub-op
// occupies bits 31:25, so each base below is funct7 << 25.

#pragma once
#include <stdint.h>

// R4-type: FMADD (opcode bit 2, funct2=0)
#define BORG_INSTR_FMADD(rd, rs1, rs2, rs3, funct3) (0x00000004U | ((funct3) << 12) | ((rs3) << 27) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))

// R4-type: FTEX (opcode bit 2, funct2=1)
#define BORG_INSTR_FTEX(rd, rs1, rs2, rs3, funct3) (0x02000004U | ((funct3) << 12) | ((rs3) << 27) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))

#define BORG_INSTR_FADD(rd, rs1, rs2, funct3) (0x00000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FMUL(rd, rs1, rs2, funct3) (0x08000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FNEG(rd, rs1, funct3) (0x0C000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FSTEP(rd, rs1, funct3) (0x10000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FRCP(rd, rs1, funct3) (0x14000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_IADD(rd, rs1, rs2, funct3) (0x1C000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_ISHL(rd, rs1, rs2, funct3) (0x20000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_ISHR(rd, rs1, rs2, funct3) (0x24000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_IMUL(rd, rs1, rs2, funct3) (0x28000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_ISUB(rd, rs1, rs2, funct3) (0x60000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_IAND(rd, rs1, rs2, funct3) (0x64000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_IOR(rd, rs1, rs2, funct3) (0x68000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_IXOR(rd, rs1, rs2, funct3) (0x6C000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_ISLT(rd, rs1, rs2, funct3) (0x70000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_ISEQ(rd, rs1, rs2, funct3) (0x74000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_I2F(rd, rs1, funct3) (0x2C000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_F2I(rd, rs1, funct3) (0x30000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FRSQ(rd, rs1, funct3) (0x34000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_FSRGB(rd, rs1, funct3) (0x38000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_DDX(rd, rs1, funct3) (0x3C000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_DDY(rd, rs1, funct3) (0x40000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_LOAD(rd, rs1, funct3) (0x44000000U | ((funct3) << 12) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_STORE(rs1, rs2, funct3) (0x48000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15))
#define BORG_INSTR_BRZ(rs1, target, funct3) (0x4C000000U | ((funct3) << 12) | ((((uint32_t)(target) >> 5) & 0x1FU) << 20) | ((rs1) << 15) | (((uint32_t)(target) & 0x1FU) << 7))
#define BORG_INSTR_BRNZ(rs1, target, funct3) (0x50000000U | ((funct3) << 12) | ((((uint32_t)(target) >> 5) & 0x1FU) << 20) | ((rs1) << 15) | (((uint32_t)(target) & 0x1FU) << 7))
#define BORG_INSTR_EXPUSH(rs1, funct3) (0x54000000U | ((funct3) << 12) | ((rs1) << 15))
#define BORG_INSTR_EXELSE(funct3) (0x58000000U | ((funct3) << 12))
#define BORG_INSTR_EXPOP(funct3) (0x5C000000U | ((funct3) << 12))
#define BORG_INSTR_BARRIER(funct3) (0x78000000U | ((funct3) << 12))
#define BORG_INSTR_EXANY(rd, funct3) (0x7C000000U | ((funct3) << 12) | ((rd) << 7))
#define BORG_INSTR_ZTEST(funct3) (0x80000000U | ((funct3) << 12))

// Special: HALT (an all-zero instruction word)
#define BORG_INSTR_HALT                           0x00000000U
