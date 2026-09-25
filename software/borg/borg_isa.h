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

// R4-type: TEX rd, u, v, ctl-register (funct2=2) and TEXA w, lod, dref (funct2=3)
#define BORG_INSTR_TEX(rd, rs1, rs2, rs3, funct3) (0x04000004U | ((funct3) << 12) | ((rs3) << 27) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_TEXA(rs1, rs2, rs3, funct3) (0x06000004U | ((funct3) << 12) | ((rs3) << 27) | ((rs2) << 20) | ((rs1) << 15))

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
#define BORG_INSTR_SOUT(rs2, index, funct3) (0x84000000U | ((funct3) << 12) | ((rs2) << 20) | ((((uint32_t)(index) >> 5) & 0x1FU) << 15) | (((uint32_t)(index) & 0x1FU) << 7))
#define BORG_INSTR_FATTR(rd, index) (0x88000000U | ((((uint32_t)(index) >> 5) & 0x1FU) << 20) | (((uint32_t)(index) & 0x1FU) << 15) | ((rd) << 7))
#define BORG_INSTR_SMASK(rd, funct3) (0x8C000000U | ((funct3) << 12) | ((rd) << 7))
#define BORG_INSTR_ATTIDX(rd, funct3) (0x90000000U | ((funct3) << 12) | ((rd) << 7))
#define BORG_INSTR_TLD(rd, funct3) (0x9C000000U | ((funct3) << 12) | ((rd) << 7))
#define BORG_INSTR_JMP(target) (0xA0000000U | ((((uint32_t)(target) >> 15) & 0x7U) << 12) | ((((uint32_t)(target) >> 10) & 0x1FU) << 20) | ((((uint32_t)(target) >> 5) & 0x1FU) << 15) | (((uint32_t)(target) & 0x1FU) << 7))
#define BORG_INSTR_ISRL(rd, rs1, rs2, funct3) (0x94000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))
#define BORG_INSTR_ISLTU(rd, rs1, rs2, funct3) (0x98000000U | ((funct3) << 12) | ((rs2) << 20) | ((rs1) << 15) | ((rd) << 7))

// Special: HALT (an all-zero instruction word)
#define BORG_INSTR_HALT                           0x00000000U

// Texel format codes for a texture descriptor (TexFormat.scala)
#define BORG_TEX_FORMAT_R8_UNORM                     1
#define BORG_TEX_FORMAT_R8_SNORM                     2
#define BORG_TEX_FORMAT_R8_UINT                      3
#define BORG_TEX_FORMAT_R8_SINT                      4
#define BORG_TEX_FORMAT_R8_SRGB                      5
#define BORG_TEX_FORMAT_R8G8_UNORM                   6
#define BORG_TEX_FORMAT_R8G8_SNORM                   7
#define BORG_TEX_FORMAT_R8G8_UINT                    8
#define BORG_TEX_FORMAT_R8G8_SINT                    9
#define BORG_TEX_FORMAT_R8G8_SRGB                    10
#define BORG_TEX_FORMAT_R8G8B8A8_UNORM               11
#define BORG_TEX_FORMAT_R8G8B8A8_SNORM               12
#define BORG_TEX_FORMAT_R8G8B8A8_UINT                13
#define BORG_TEX_FORMAT_R8G8B8A8_SINT                14
#define BORG_TEX_FORMAT_R8G8B8A8_SRGB                15
#define BORG_TEX_FORMAT_B8G8R8A8_UNORM               16
#define BORG_TEX_FORMAT_B8G8R8A8_SRGB                17
#define BORG_TEX_FORMAT_R16_UNORM                    18
#define BORG_TEX_FORMAT_R16_SNORM                    19
#define BORG_TEX_FORMAT_R16_UINT                     20
#define BORG_TEX_FORMAT_R16_SINT                     21
#define BORG_TEX_FORMAT_R16_SFLOAT                   22
#define BORG_TEX_FORMAT_R16G16_UNORM                 23
#define BORG_TEX_FORMAT_R16G16_SNORM                 24
#define BORG_TEX_FORMAT_R16G16_UINT                  25
#define BORG_TEX_FORMAT_R16G16_SINT                  26
#define BORG_TEX_FORMAT_R16G16_SFLOAT                27
#define BORG_TEX_FORMAT_R16G16B16A16_UNORM           28
#define BORG_TEX_FORMAT_R16G16B16A16_SNORM           29
#define BORG_TEX_FORMAT_R16G16B16A16_UINT            30
#define BORG_TEX_FORMAT_R16G16B16A16_SINT            31
#define BORG_TEX_FORMAT_R16G16B16A16_SFLOAT          32
#define BORG_TEX_FORMAT_R32_UINT                     33
#define BORG_TEX_FORMAT_R32_SINT                     34
#define BORG_TEX_FORMAT_R32_SFLOAT                   35
#define BORG_TEX_FORMAT_R32G32_UINT                  36
#define BORG_TEX_FORMAT_R32G32_SINT                  37
#define BORG_TEX_FORMAT_R32G32_SFLOAT                38
#define BORG_TEX_FORMAT_R32G32B32A32_UINT            39
#define BORG_TEX_FORMAT_R32G32B32A32_SINT            40
#define BORG_TEX_FORMAT_R32G32B32A32_SFLOAT          41
#define BORG_TEX_FORMAT_A2B10G10R10_UNORM_PACK32     42
#define BORG_TEX_FORMAT_A2B10G10R10_UINT_PACK32      43
#define BORG_TEX_FORMAT_R5G6B5_UNORM_PACK16          44
#define BORG_TEX_FORMAT_A1R5G5B5_UNORM_PACK16        45
#define BORG_TEX_FORMAT_B4G4R4A4_UNORM_PACK16        46
#define BORG_TEX_FORMAT_B10G11R11_UFLOAT_PACK32      47
#define BORG_TEX_FORMAT_E5B9G9R9_UFLOAT_PACK32       48
#define BORG_TEX_FORMAT_D16_UNORM                    49
#define BORG_TEX_FORMAT_X8_D24_UNORM_PACK32          50
#define BORG_TEX_FORMAT_D32_SFLOAT                   51
