// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from hardware/borg/isa by scripts/isa_gen.py; do not edit.

#pragma once
#include <stdint.h>

#define BORG_MATCH_FMADD 0x00000004U
#define BORG_MASK_FMADD 0x0600007fU
#define BORG_INSTR_FMADD(rd, rs1, rs2, rs3, f3) (BORG_MATCH_FMADD | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rs3) & 0x1fU) << 27) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_TEX 0x04000004U
#define BORG_MASK_TEX 0x0600007fU
#define BORG_INSTR_TEX(rd, rs1, rs2, rs3, f3) (BORG_MATCH_TEX | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rs3) & 0x1fU) << 27) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_TEXA 0x06000004U
#define BORG_MASK_TEXA 0x06000fffU
#define BORG_INSTR_TEXA(rs1, rs2, rs3, f3) (BORG_MATCH_TEXA | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rs3) & 0x1fU) << 27) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FADD 0x00000000U
#define BORG_MASK_FADD 0xfe00007fU
#define BORG_INSTR_FADD(rd, rs1, rs2, f3) (BORG_MATCH_FADD | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FMUL 0x08000000U
#define BORG_MASK_FMUL 0xfe00007fU
#define BORG_INSTR_FMUL(rd, rs1, rs2, f3) (BORG_MATCH_FMUL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FNEG 0x0c000000U
#define BORG_MASK_FNEG 0xfff0007fU
#define BORG_INSTR_FNEG(rd, rs1, f3) (BORG_MATCH_FNEG | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FMOV 0xa4000000U
#define BORG_MASK_FMOV 0xfff0007fU
#define BORG_INSTR_FMOV(rd, rs1, f3) (BORG_MATCH_FMOV | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FSTEP 0x10000000U
#define BORG_MASK_FSTEP 0xfff0007fU
#define BORG_INSTR_FSTEP(rd, rs1, f3) (BORG_MATCH_FSTEP | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FRCP 0x14000000U
#define BORG_MASK_FRCP 0xfff0007fU
#define BORG_INSTR_FRCP(rd, rs1, f3) (BORG_MATCH_FRCP | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_IADD 0x1c000000U
#define BORG_MASK_IADD 0xfe00007fU
#define BORG_INSTR_IADD(rd, rs1, rs2, f3) (BORG_MATCH_IADD | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ISHL 0x20000000U
#define BORG_MASK_ISHL 0xfe00007fU
#define BORG_INSTR_ISHL(rd, rs1, rs2, f3) (BORG_MATCH_ISHL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ISHR 0x24000000U
#define BORG_MASK_ISHR 0xfe00007fU
#define BORG_INSTR_ISHR(rd, rs1, rs2, f3) (BORG_MATCH_ISHR | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_IMUL 0x28000000U
#define BORG_MASK_IMUL 0xfe00007fU
#define BORG_INSTR_IMUL(rd, rs1, rs2, f3) (BORG_MATCH_IMUL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ISUB 0x60000000U
#define BORG_MASK_ISUB 0xfe00007fU
#define BORG_INSTR_ISUB(rd, rs1, rs2, f3) (BORG_MATCH_ISUB | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_IAND 0x64000000U
#define BORG_MASK_IAND 0xfe00007fU
#define BORG_INSTR_IAND(rd, rs1, rs2, f3) (BORG_MATCH_IAND | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_IOR 0x68000000U
#define BORG_MASK_IOR 0xfe00007fU
#define BORG_INSTR_IOR(rd, rs1, rs2, f3) (BORG_MATCH_IOR | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_IXOR 0x6c000000U
#define BORG_MASK_IXOR 0xfe00007fU
#define BORG_INSTR_IXOR(rd, rs1, rs2, f3) (BORG_MATCH_IXOR | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ISLT 0x70000000U
#define BORG_MASK_ISLT 0xfe00007fU
#define BORG_INSTR_ISLT(rd, rs1, rs2, f3) (BORG_MATCH_ISLT | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ISEQ 0x74000000U
#define BORG_MASK_ISEQ 0xfe00007fU
#define BORG_INSTR_ISEQ(rd, rs1, rs2, f3) (BORG_MATCH_ISEQ | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_I2F 0x2c000000U
#define BORG_MASK_I2F 0xfff0007fU
#define BORG_INSTR_I2F(rd, rs1, f3) (BORG_MATCH_I2F | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_F2I 0x30000000U
#define BORG_MASK_F2I 0xfff0007fU
#define BORG_INSTR_F2I(rd, rs1, f3) (BORG_MATCH_F2I | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_F2H 0xa8000000U
#define BORG_MASK_F2H 0xfff0007fU
#define BORG_INSTR_F2H(rd, rs1, f3) (BORG_MATCH_F2H | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_H2F 0xac000000U
#define BORG_MASK_H2F 0xfff0007fU
#define BORG_INSTR_H2F(rd, rs1, f3) (BORG_MATCH_H2F | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FRSQ 0x34000000U
#define BORG_MASK_FRSQ 0xfff0007fU
#define BORG_INSTR_FRSQ(rd, rs1, f3) (BORG_MATCH_FRSQ | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FSRGB 0x38000000U
#define BORG_MASK_FSRGB 0xfff0007fU
#define BORG_INSTR_FSRGB(rd, rs1, f3) (BORG_MATCH_FSRGB | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_DDX 0x3c000000U
#define BORG_MASK_DDX 0xfe00007fU
#define BORG_INSTR_DDX(rd, rs1, fine, f3) (BORG_MATCH_DDX | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(fine) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_DDY 0x40000000U
#define BORG_MASK_DDY 0xfe00007fU
#define BORG_INSTR_DDY(rd, rs1, fine, f3) (BORG_MATCH_DDY | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(fine) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_LOAD 0x44000000U
#define BORG_MASK_LOAD 0xfff0007fU
#define BORG_INSTR_LOAD(rd, rs1, f3) (BORG_MATCH_LOAD | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_STORE 0x48000000U
#define BORG_MASK_STORE 0xfe00007fU
#define BORG_INSTR_STORE(rs1, rs2, private, f3) (BORG_MATCH_STORE | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(private) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_BRZ 0x4c000000U
#define BORG_MASK_BRZ 0xfe00007fU
#define BORG_INSTR_BRZ(rs1, offset, f3) (BORG_MATCH_BRZ | (((uint32_t)(rs1) & 0x1fU) << 15) | ((((uint32_t)(offset) >> 5) & 0x1fU) << 20) | (((uint32_t)(offset) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_BRNZ 0x50000000U
#define BORG_MASK_BRNZ 0xfe00007fU
#define BORG_INSTR_BRNZ(rs1, offset, f3) (BORG_MATCH_BRNZ | (((uint32_t)(rs1) & 0x1fU) << 15) | ((((uint32_t)(offset) >> 5) & 0x1fU) << 20) | (((uint32_t)(offset) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_EXPUSH 0x54000000U
#define BORG_MASK_EXPUSH 0xfff00fffU
#define BORG_INSTR_EXPUSH(rs1, f3) (BORG_MATCH_EXPUSH | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_EXELSE 0x58000000U
#define BORG_MASK_EXELSE 0xffff8fffU
#define BORG_INSTR_EXELSE(f3) (BORG_MATCH_EXELSE | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_EXPOP 0x5c000000U
#define BORG_MASK_EXPOP 0xffff8fffU
#define BORG_INSTR_EXPOP(f3) (BORG_MATCH_EXPOP | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_BARRIER 0x78000000U
#define BORG_MASK_BARRIER 0xffff8fffU
#define BORG_INSTR_BARRIER(f3) (BORG_MATCH_BARRIER | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_EXANY 0x7c000000U
#define BORG_MASK_EXANY 0xffff807fU
#define BORG_INSTR_EXANY(rd, f3) (BORG_MATCH_EXANY | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ZTEST 0x80000000U
#define BORG_MASK_ZTEST 0xffff8fffU
#define BORG_INSTR_ZTEST(f3) (BORG_MATCH_ZTEST | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_SOUT 0x84000000U
#define BORG_MASK_SOUT 0xfe00007fU
#define BORG_INSTR_SOUT(rs2, index, f3) (BORG_MATCH_SOUT | (((uint32_t)(rs2) & 0x1fU) << 20) | ((((uint32_t)(index) >> 5) & 0x1fU) << 15) | (((uint32_t)(index) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_FATTR 0x88000000U
#define BORG_MASK_FATTR 0xfe00707fU
#define BORG_INSTR_FATTR(rd, aindex) (BORG_MATCH_FATTR | (((uint32_t)(rd) & 0x1fU) << 7) | ((((uint32_t)(aindex) >> 5) & 0x1fU) << 20) | (((uint32_t)(aindex) & 0x1fU) << 15))
#define BORG_MATCH_SMASK 0x8c000000U
#define BORG_MASK_SMASK 0xffff807fU
#define BORG_INSTR_SMASK(rd, f3) (BORG_MATCH_SMASK | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ATTIDX 0x90000000U
#define BORG_MASK_ATTIDX 0xffff807fU
#define BORG_INSTR_ATTIDX(rd, f3) (BORG_MATCH_ATTIDX | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ISRL 0x94000000U
#define BORG_MASK_ISRL 0xfe00007fU
#define BORG_INSTR_ISRL(rd, rs1, rs2, f3) (BORG_MATCH_ISRL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_ISLTU 0x98000000U
#define BORG_MASK_ISLTU 0xfe00007fU
#define BORG_INSTR_ISLTU(rd, rs1, rs2, f3) (BORG_MATCH_ISLTU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_TLD 0x9c000000U
#define BORG_MASK_TLD 0xffff807fU
#define BORG_INSTR_TLD(rd, f3) (BORG_MATCH_TLD | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_JMP 0xa0000000U
#define BORG_MASK_JMP 0xfe00007fU
#define BORG_INSTR_JMP(joffset) (BORG_MATCH_JMP | ((((uint32_t)(joffset) >> 15) & 0x7U) << 12) | ((((uint32_t)(joffset) >> 10) & 0x1fU) << 20) | ((((uint32_t)(joffset) >> 5) & 0x1fU) << 15) | (((uint32_t)(joffset) & 0x1fU) << 7))
#define BORG_MATCH_LUI 0x00000037U
#define BORG_MASK_LUI 0x0000007fU
#define BORG_INSTR_LUI(rd, imm20) (BORG_MATCH_LUI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(imm20) & 0xfffffU) << 12))
#define BORG_MATCH_AUIPC 0x00000017U
#define BORG_MASK_AUIPC 0x0000007fU
#define BORG_INSTR_AUIPC(rd, imm20) (BORG_MATCH_AUIPC | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(imm20) & 0xfffffU) << 12))
#define BORG_MATCH_ADDI 0x00000013U
#define BORG_MASK_ADDI 0x0000707fU
#define BORG_INSTR_ADDI(rd, rs1, imm12) (BORG_MATCH_ADDI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_SLTI 0x00002013U
#define BORG_MASK_SLTI 0x0000707fU
#define BORG_INSTR_SLTI(rd, rs1, imm12) (BORG_MATCH_SLTI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_SLTIU 0x00003013U
#define BORG_MASK_SLTIU 0x0000707fU
#define BORG_INSTR_SLTIU(rd, rs1, imm12) (BORG_MATCH_SLTIU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_XORI 0x00004013U
#define BORG_MASK_XORI 0x0000707fU
#define BORG_INSTR_XORI(rd, rs1, imm12) (BORG_MATCH_XORI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_ORI 0x00006013U
#define BORG_MASK_ORI 0x0000707fU
#define BORG_INSTR_ORI(rd, rs1, imm12) (BORG_MATCH_ORI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_ANDI 0x00007013U
#define BORG_MASK_ANDI 0x0000707fU
#define BORG_INSTR_ANDI(rd, rs1, imm12) (BORG_MATCH_ANDI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_SLLI 0x00001013U
#define BORG_MASK_SLLI 0xfe00707fU
#define BORG_INSTR_SLLI(rd, rs1, shamtw) (BORG_MATCH_SLLI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(shamtw) & 0x1fU) << 20))
#define BORG_MATCH_SRLI 0x00005013U
#define BORG_MASK_SRLI 0xfe00707fU
#define BORG_INSTR_SRLI(rd, rs1, shamtw) (BORG_MATCH_SRLI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(shamtw) & 0x1fU) << 20))
#define BORG_MATCH_SRAI 0x40005013U
#define BORG_MASK_SRAI 0xfe00707fU
#define BORG_INSTR_SRAI(rd, rs1, shamtw) (BORG_MATCH_SRAI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(shamtw) & 0x1fU) << 20))
#define BORG_MATCH_ADD 0x00000033U
#define BORG_MASK_ADD 0xfe00707fU
#define BORG_INSTR_ADD(rd, rs1, rs2) (BORG_MATCH_ADD | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_SUB 0x40000033U
#define BORG_MASK_SUB 0xfe00707fU
#define BORG_INSTR_SUB(rd, rs1, rs2) (BORG_MATCH_SUB | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_SLL 0x00001033U
#define BORG_MASK_SLL 0xfe00707fU
#define BORG_INSTR_SLL(rd, rs1, rs2) (BORG_MATCH_SLL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_SLT 0x00002033U
#define BORG_MASK_SLT 0xfe00707fU
#define BORG_INSTR_SLT(rd, rs1, rs2) (BORG_MATCH_SLT | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_SLTU 0x00003033U
#define BORG_MASK_SLTU 0xfe00707fU
#define BORG_INSTR_SLTU(rd, rs1, rs2) (BORG_MATCH_SLTU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_XOR 0x00004033U
#define BORG_MASK_XOR 0xfe00707fU
#define BORG_INSTR_XOR(rd, rs1, rs2) (BORG_MATCH_XOR | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_SRL 0x00005033U
#define BORG_MASK_SRL 0xfe00707fU
#define BORG_INSTR_SRL(rd, rs1, rs2) (BORG_MATCH_SRL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_SRA 0x40005033U
#define BORG_MASK_SRA 0xfe00707fU
#define BORG_INSTR_SRA(rd, rs1, rs2) (BORG_MATCH_SRA | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_OR 0x00006033U
#define BORG_MASK_OR 0xfe00707fU
#define BORG_INSTR_OR(rd, rs1, rs2) (BORG_MATCH_OR | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_AND 0x00007033U
#define BORG_MASK_AND 0xfe00707fU
#define BORG_INSTR_AND(rd, rs1, rs2) (BORG_MATCH_AND | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_LB 0x00000003U
#define BORG_MASK_LB 0x0000707fU
#define BORG_INSTR_LB(rd, rs1, imm12) (BORG_MATCH_LB | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_LH 0x00001003U
#define BORG_MASK_LH 0x0000707fU
#define BORG_INSTR_LH(rd, rs1, imm12) (BORG_MATCH_LH | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_LW 0x00002003U
#define BORG_MASK_LW 0x0000707fU
#define BORG_INSTR_LW(rd, rs1, imm12) (BORG_MATCH_LW | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_LBU 0x00004003U
#define BORG_MASK_LBU 0x0000707fU
#define BORG_INSTR_LBU(rd, rs1, imm12) (BORG_MATCH_LBU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_LHU 0x00005003U
#define BORG_MASK_LHU 0x0000707fU
#define BORG_INSTR_LHU(rd, rs1, imm12) (BORG_MATCH_LHU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(imm12) & 0xfffU) << 20))
#define BORG_MATCH_SW 0x00002023U
#define BORG_MASK_SW 0x0000707fU
#define BORG_INSTR_SW(simm, rs1, rs2) (BORG_MATCH_SW | ((((uint32_t)(simm) >> 5) & 0x7fU) << 25) | (((uint32_t)(simm) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_JAL 0x0000006fU
#define BORG_MASK_JAL 0x0000007fU
#define BORG_INSTR_JAL(rd, joff) (BORG_MATCH_JAL | (((uint32_t)(rd) & 0x1fU) << 7) | ((((uint32_t)(joff) >> 20) & 0x1U) << 31) | ((((uint32_t)(joff) >> 1) & 0x3ffU) << 21) | ((((uint32_t)(joff) >> 11) & 0x1U) << 20) | ((((uint32_t)(joff) >> 12) & 0xffU) << 12))
#define BORG_MATCH_BEQ 0x00000063U
#define BORG_MASK_BEQ 0x0000707fU
#define BORG_INSTR_BEQ(boff, rs1, rs2) (BORG_MATCH_BEQ | ((((uint32_t)(boff) >> 12) & 0x1U) << 31) | ((((uint32_t)(boff) >> 5) & 0x3fU) << 25) | ((((uint32_t)(boff) >> 1) & 0xfU) << 8) | ((((uint32_t)(boff) >> 11) & 0x1U) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_BNE 0x00001063U
#define BORG_MASK_BNE 0x0000707fU
#define BORG_INSTR_BNE(boff, rs1, rs2) (BORG_MATCH_BNE | ((((uint32_t)(boff) >> 12) & 0x1U) << 31) | ((((uint32_t)(boff) >> 5) & 0x3fU) << 25) | ((((uint32_t)(boff) >> 1) & 0xfU) << 8) | ((((uint32_t)(boff) >> 11) & 0x1U) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_BLT 0x00004063U
#define BORG_MASK_BLT 0x0000707fU
#define BORG_INSTR_BLT(boff, rs1, rs2) (BORG_MATCH_BLT | ((((uint32_t)(boff) >> 12) & 0x1U) << 31) | ((((uint32_t)(boff) >> 5) & 0x3fU) << 25) | ((((uint32_t)(boff) >> 1) & 0xfU) << 8) | ((((uint32_t)(boff) >> 11) & 0x1U) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_BGE 0x00005063U
#define BORG_MASK_BGE 0x0000707fU
#define BORG_INSTR_BGE(boff, rs1, rs2) (BORG_MATCH_BGE | ((((uint32_t)(boff) >> 12) & 0x1U) << 31) | ((((uint32_t)(boff) >> 5) & 0x3fU) << 25) | ((((uint32_t)(boff) >> 1) & 0xfU) << 8) | ((((uint32_t)(boff) >> 11) & 0x1U) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_BLTU 0x00006063U
#define BORG_MASK_BLTU 0x0000707fU
#define BORG_INSTR_BLTU(boff, rs1, rs2) (BORG_MATCH_BLTU | ((((uint32_t)(boff) >> 12) & 0x1U) << 31) | ((((uint32_t)(boff) >> 5) & 0x3fU) << 25) | ((((uint32_t)(boff) >> 1) & 0xfU) << 8) | ((((uint32_t)(boff) >> 11) & 0x1U) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_BGEU 0x00007063U
#define BORG_MASK_BGEU 0x0000707fU
#define BORG_INSTR_BGEU(boff, rs1, rs2) (BORG_MATCH_BGEU | ((((uint32_t)(boff) >> 12) & 0x1U) << 31) | ((((uint32_t)(boff) >> 5) & 0x3fU) << 25) | ((((uint32_t)(boff) >> 1) & 0xfU) << 8) | ((((uint32_t)(boff) >> 11) & 0x1U) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MUL 0x02000033U
#define BORG_MASK_MUL 0xfe00707fU
#define BORG_INSTR_MUL(rd, rs1, rs2) (BORG_MATCH_MUL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MULH 0x02001033U
#define BORG_MASK_MULH 0xfe00707fU
#define BORG_INSTR_MULH(rd, rs1, rs2) (BORG_MATCH_MULH | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MULHSU 0x02002033U
#define BORG_MASK_MULHSU 0xfe00707fU
#define BORG_INSTR_MULHSU(rd, rs1, rs2) (BORG_MATCH_MULHSU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MULHU 0x02003033U
#define BORG_MASK_MULHU 0xfe00707fU
#define BORG_INSTR_MULHU(rd, rs1, rs2) (BORG_MATCH_MULHU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_CZERO_EQZ 0x0e005033U
#define BORG_MASK_CZERO_EQZ 0xfe00707fU
#define BORG_INSTR_CZERO_EQZ(rd, rs1, rs2) (BORG_MATCH_CZERO_EQZ | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_CZERO_NEZ 0x0e007033U
#define BORG_MASK_CZERO_NEZ 0xfe00707fU
#define BORG_INSTR_CZERO_NEZ(rd, rs1, rs2) (BORG_MATCH_CZERO_NEZ | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_ANDN 0x40007033U
#define BORG_MASK_ANDN 0xfe00707fU
#define BORG_INSTR_ANDN(rd, rs1, rs2) (BORG_MATCH_ANDN | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_ORN 0x40006033U
#define BORG_MASK_ORN 0xfe00707fU
#define BORG_INSTR_ORN(rd, rs1, rs2) (BORG_MATCH_ORN | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_XNOR 0x40004033U
#define BORG_MASK_XNOR 0xfe00707fU
#define BORG_INSTR_XNOR(rd, rs1, rs2) (BORG_MATCH_XNOR | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MIN 0x0a004033U
#define BORG_MASK_MIN 0xfe00707fU
#define BORG_INSTR_MIN(rd, rs1, rs2) (BORG_MATCH_MIN | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MINU 0x0a005033U
#define BORG_MASK_MINU 0xfe00707fU
#define BORG_INSTR_MINU(rd, rs1, rs2) (BORG_MATCH_MINU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MAX 0x0a006033U
#define BORG_MASK_MAX 0xfe00707fU
#define BORG_INSTR_MAX(rd, rs1, rs2) (BORG_MATCH_MAX | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_MAXU 0x0a007033U
#define BORG_MASK_MAXU 0xfe00707fU
#define BORG_INSTR_MAXU(rd, rs1, rs2) (BORG_MATCH_MAXU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_ROL 0x60001033U
#define BORG_MASK_ROL 0xfe00707fU
#define BORG_INSTR_ROL(rd, rs1, rs2) (BORG_MATCH_ROL | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_ROR 0x60005033U
#define BORG_MASK_ROR 0xfe00707fU
#define BORG_INSTR_ROR(rd, rs1, rs2) (BORG_MATCH_ROR | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_RORI 0x60005013U
#define BORG_MASK_RORI 0xfe00707fU
#define BORG_INSTR_RORI(rd, rs1, shamtw) (BORG_MATCH_RORI | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(shamtw) & 0x1fU) << 20))
#define BORG_MATCH_CLZ 0x60001013U
#define BORG_MASK_CLZ 0xfff0707fU
#define BORG_INSTR_CLZ(rd, rs1) (BORG_MATCH_CLZ | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_CTZ 0x60101013U
#define BORG_MASK_CTZ 0xfff0707fU
#define BORG_INSTR_CTZ(rd, rs1) (BORG_MATCH_CTZ | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_CPOP 0x60201013U
#define BORG_MASK_CPOP 0xfff0707fU
#define BORG_INSTR_CPOP(rd, rs1) (BORG_MATCH_CPOP | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_SEXT_B 0x60401013U
#define BORG_MASK_SEXT_B 0xfff0707fU
#define BORG_INSTR_SEXT_B(rd, rs1) (BORG_MATCH_SEXT_B | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_SEXT_H 0x60501013U
#define BORG_MASK_SEXT_H 0xfff0707fU
#define BORG_INSTR_SEXT_H(rd, rs1) (BORG_MATCH_SEXT_H | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_ZEXT_H 0x08004033U
#define BORG_MASK_ZEXT_H 0xfff0707fU
#define BORG_INSTR_ZEXT_H(rd, rs1) (BORG_MATCH_ZEXT_H | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_REV8 0x69805013U
#define BORG_MASK_REV8 0xfff0707fU
#define BORG_INSTR_REV8(rd, rs1) (BORG_MATCH_REV8 | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_FADD_S 0x00000053U
#define BORG_MASK_FADD_S 0xfe00007fU
#define BORG_INSTR_FADD_S(rd, rs1, rs2, rm) (BORG_MATCH_FADD_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FSUB_S 0x08000053U
#define BORG_MASK_FSUB_S 0xfe00007fU
#define BORG_INSTR_FSUB_S(rd, rs1, rs2, rm) (BORG_MATCH_FSUB_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FMUL_S 0x10000053U
#define BORG_MASK_FMUL_S 0xfe00007fU
#define BORG_INSTR_FMUL_S(rd, rs1, rs2, rm) (BORG_MATCH_FMUL_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FSGNJ_S 0x20000053U
#define BORG_MASK_FSGNJ_S 0xfe00707fU
#define BORG_INSTR_FSGNJ_S(rd, rs1, rs2) (BORG_MATCH_FSGNJ_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FSGNJN_S 0x20001053U
#define BORG_MASK_FSGNJN_S 0xfe00707fU
#define BORG_INSTR_FSGNJN_S(rd, rs1, rs2) (BORG_MATCH_FSGNJN_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FSGNJX_S 0x20002053U
#define BORG_MASK_FSGNJX_S 0xfe00707fU
#define BORG_INSTR_FSGNJX_S(rd, rs1, rs2) (BORG_MATCH_FSGNJX_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FMIN_S 0x28000053U
#define BORG_MASK_FMIN_S 0xfe00707fU
#define BORG_INSTR_FMIN_S(rd, rs1, rs2) (BORG_MATCH_FMIN_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FMAX_S 0x28001053U
#define BORG_MASK_FMAX_S 0xfe00707fU
#define BORG_INSTR_FMAX_S(rd, rs1, rs2) (BORG_MATCH_FMAX_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FEQ_S 0xa0002053U
#define BORG_MASK_FEQ_S 0xfe00707fU
#define BORG_INSTR_FEQ_S(rd, rs1, rs2) (BORG_MATCH_FEQ_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FLT_S 0xa0001053U
#define BORG_MASK_FLT_S 0xfe00707fU
#define BORG_INSTR_FLT_S(rd, rs1, rs2) (BORG_MATCH_FLT_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FLE_S 0xa0000053U
#define BORG_MASK_FLE_S 0xfe00707fU
#define BORG_INSTR_FLE_S(rd, rs1, rs2) (BORG_MATCH_FLE_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20))
#define BORG_MATCH_FCLASS_S 0xe0001053U
#define BORG_MASK_FCLASS_S 0xfff0707fU
#define BORG_INSTR_FCLASS_S(rd, rs1) (BORG_MATCH_FCLASS_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15))
#define BORG_MATCH_FCVT_W_S 0xc0000053U
#define BORG_MASK_FCVT_W_S 0xfff0007fU
#define BORG_INSTR_FCVT_W_S(rd, rs1, rm) (BORG_MATCH_FCVT_W_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FCVT_WU_S 0xc0100053U
#define BORG_MASK_FCVT_WU_S 0xfff0007fU
#define BORG_INSTR_FCVT_WU_S(rd, rs1, rm) (BORG_MATCH_FCVT_WU_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FCVT_S_W 0xd0000053U
#define BORG_MASK_FCVT_S_W 0xfff0007fU
#define BORG_INSTR_FCVT_S_W(rd, rs1, rm) (BORG_MATCH_FCVT_S_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FCVT_S_WU 0xd0100053U
#define BORG_MASK_FCVT_S_WU 0xfff0007fU
#define BORG_INSTR_FCVT_S_WU(rd, rs1, rm) (BORG_MATCH_FCVT_S_WU | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FCVT_S_H 0x40200053U
#define BORG_MASK_FCVT_S_H 0xfff0007fU
#define BORG_INSTR_FCVT_S_H(rd, rs1, rm) (BORG_MATCH_FCVT_S_H | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FCVT_H_S 0x44000053U
#define BORG_MASK_FCVT_H_S 0xfff0007fU
#define BORG_INSTR_FCVT_H_S(rd, rs1, rm) (BORG_MATCH_FCVT_H_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FMADD_S 0x00000043U
#define BORG_MASK_FMADD_S 0x0600007fU
#define BORG_INSTR_FMADD_S(rd, rs1, rs2, rs3, rm) (BORG_MATCH_FMADD_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rs3) & 0x1fU) << 27) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FMSUB_S 0x00000047U
#define BORG_MASK_FMSUB_S 0x0600007fU
#define BORG_INSTR_FMSUB_S(rd, rs1, rs2, rs3, rm) (BORG_MATCH_FMSUB_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rs3) & 0x1fU) << 27) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FNMSUB_S 0x0000004bU
#define BORG_MASK_FNMSUB_S 0x0600007fU
#define BORG_INSTR_FNMSUB_S(rd, rs1, rs2, rs3, rm) (BORG_MATCH_FNMSUB_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rs3) & 0x1fU) << 27) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_FNMADD_S 0x0000004fU
#define BORG_MASK_FNMADD_S 0x0600007fU
#define BORG_INSTR_FNMADD_S(rd, rs1, rs2, rs3, rm) (BORG_MATCH_FNMADD_S | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(rs3) & 0x1fU) << 27) | (((uint32_t)(rm) & 0x7U) << 12))
#define BORG_MATCH_AMOADD_W 0x0000202fU
#define BORG_MASK_AMOADD_W 0xf800707fU
#define BORG_INSTR_AMOADD_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOADD_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOSWAP_W 0x0800202fU
#define BORG_MASK_AMOSWAP_W 0xf800707fU
#define BORG_INSTR_AMOSWAP_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOSWAP_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOXOR_W 0x2000202fU
#define BORG_MASK_AMOXOR_W 0xf800707fU
#define BORG_INSTR_AMOXOR_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOXOR_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOAND_W 0x6000202fU
#define BORG_MASK_AMOAND_W 0xf800707fU
#define BORG_INSTR_AMOAND_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOAND_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOOR_W 0x4000202fU
#define BORG_MASK_AMOOR_W 0xf800707fU
#define BORG_INSTR_AMOOR_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOOR_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOMIN_W 0x8000202fU
#define BORG_MASK_AMOMIN_W 0xf800707fU
#define BORG_INSTR_AMOMIN_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOMIN_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOMAX_W 0xa000202fU
#define BORG_MASK_AMOMAX_W 0xf800707fU
#define BORG_INSTR_AMOMAX_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOMAX_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOMINU_W 0xc000202fU
#define BORG_MASK_AMOMINU_W 0xf800707fU
#define BORG_INSTR_AMOMINU_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOMINU_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOMAXU_W 0xe000202fU
#define BORG_MASK_AMOMAXU_W 0xf800707fU
#define BORG_INSTR_AMOMAXU_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOMAXU_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_AMOCAS_W 0x2800202fU
#define BORG_MASK_AMOCAS_W 0xf800707fU
#define BORG_INSTR_AMOCAS_W(rd, rs1, rs2, aq, rl) (BORG_MATCH_AMOCAS_W | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(rs2) & 0x1fU) << 20) | (((uint32_t)(aq) & 0x1U) << 26) | (((uint32_t)(rl) & 0x1U) << 25))
#define BORG_MATCH_CSRRW 0x00001073U
#define BORG_MASK_CSRRW 0x0000707fU
#define BORG_INSTR_CSRRW(rd, rs1, csr) (BORG_MATCH_CSRRW | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(csr) & 0xfffU) << 20))
#define BORG_MATCH_CSRRS 0x00002073U
#define BORG_MASK_CSRRS 0x0000707fU
#define BORG_INSTR_CSRRS(rd, rs1, csr) (BORG_MATCH_CSRRS | (((uint32_t)(rd) & 0x1fU) << 7) | (((uint32_t)(rs1) & 0x1fU) << 15) | (((uint32_t)(csr) & 0xfffU) << 20))

#define BORG_INSTR_HALT 0x00000000U

/* X(NAME) for every instruction. */
#define BORG_OPCODES(X) X(FMADD) X(TEX) X(TEXA) X(FADD) X(FMUL) X(FNEG) X(FMOV) X(FSTEP) X(FRCP) X(IADD) X(ISHL) X(ISHR) X(IMUL) X(ISUB) X(IAND) X(IOR) X(IXOR) X(ISLT) X(ISEQ) X(I2F) X(F2I) X(F2H) X(H2F) X(FRSQ) X(FSRGB) X(DDX) X(DDY) X(LOAD) X(STORE) X(BRZ) X(BRNZ) X(EXPUSH) X(EXELSE) X(EXPOP) X(BARRIER) X(EXANY) X(ZTEST) X(SOUT) X(FATTR) X(SMASK) X(ATTIDX) X(ISRL) X(ISLTU) X(TLD) X(JMP) X(LUI) X(AUIPC) X(ADDI) X(SLTI) X(SLTIU) X(XORI) X(ORI) X(ANDI) X(SLLI) X(SRLI) X(SRAI) X(ADD) X(SUB) X(SLL) X(SLT) X(SLTU) X(XOR) X(SRL) X(SRA) X(OR) X(AND) X(LB) X(LH) X(LW) X(LBU) X(LHU) X(SW) X(JAL) X(BEQ) X(BNE) X(BLT) X(BGE) X(BLTU) X(BGEU) X(MUL) X(MULH) X(MULHSU) X(MULHU) X(CZERO_EQZ) X(CZERO_NEZ) X(ANDN) X(ORN) X(XNOR) X(MIN) X(MINU) X(MAX) X(MAXU) X(ROL) X(ROR) X(RORI) X(CLZ) X(CTZ) X(CPOP) X(SEXT_B) X(SEXT_H) X(ZEXT_H) X(REV8) X(FADD_S) X(FSUB_S) X(FMUL_S) X(FSGNJ_S) X(FSGNJN_S) X(FSGNJX_S) X(FMIN_S) X(FMAX_S) X(FEQ_S) X(FLT_S) X(FLE_S) X(FCLASS_S) X(FCVT_W_S) X(FCVT_WU_S) X(FCVT_S_W) X(FCVT_S_WU) X(FCVT_S_H) X(FCVT_H_S) X(FMADD_S) X(FMSUB_S) X(FNMSUB_S) X(FNMADD_S) X(AMOADD_W) X(AMOSWAP_W) X(AMOXOR_W) X(AMOAND_W) X(AMOOR_W) X(AMOMIN_W) X(AMOMAX_W) X(AMOMINU_W) X(AMOMAXU_W) X(AMOCAS_W) X(CSRRW) X(CSRRS)
