// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from hardware/borg/isa/opcodes by hardware/borg/isa/gen.py; do not edit.

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
#define BORG_INSTR_BRZ(rs1, target, f3) (BORG_MATCH_BRZ | (((uint32_t)(rs1) & 0x1fU) << 15) | ((((uint32_t)(target) >> 5) & 0x1fU) << 20) | (((uint32_t)(target) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
#define BORG_MATCH_BRNZ 0x50000000U
#define BORG_MASK_BRNZ 0xfe00007fU
#define BORG_INSTR_BRNZ(rs1, target, f3) (BORG_MATCH_BRNZ | (((uint32_t)(rs1) & 0x1fU) << 15) | ((((uint32_t)(target) >> 5) & 0x1fU) << 20) | (((uint32_t)(target) & 0x1fU) << 7) | (((uint32_t)(f3) & 0x7U) << 12))
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
#define BORG_INSTR_JMP(jtarget) (BORG_MATCH_JMP | ((((uint32_t)(jtarget) >> 15) & 0x7U) << 12) | ((((uint32_t)(jtarget) >> 10) & 0x1fU) << 20) | ((((uint32_t)(jtarget) >> 5) & 0x1fU) << 15) | (((uint32_t)(jtarget) & 0x1fU) << 7))

#define BORG_INSTR_HALT 0x00000000U

/* X(NAME) for every instruction. */
#define BORG_OPCODES(X) X(FMADD) X(TEX) X(TEXA) X(FADD) X(FMUL) X(FNEG) X(FMOV) X(FSTEP) X(FRCP) X(IADD) X(ISHL) X(ISHR) X(IMUL) X(ISUB) X(IAND) X(IOR) X(IXOR) X(ISLT) X(ISEQ) X(I2F) X(F2I) X(F2H) X(H2F) X(FRSQ) X(FSRGB) X(DDX) X(DDY) X(LOAD) X(STORE) X(BRZ) X(BRNZ) X(EXPUSH) X(EXELSE) X(EXPOP) X(BARRIER) X(EXANY) X(ZTEST) X(SOUT) X(FATTR) X(SMASK) X(ATTIDX) X(ISRL) X(ISLTU) X(TLD) X(JMP)
