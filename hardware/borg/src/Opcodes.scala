// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
// GENERATED from hardware/borg/isa by scripts/isa_gen.py; do not edit.

package borg

object Opcodes {
  val FIELD_RD = (11, 7)
  val FIELD_RS1 = (19, 15)
  val FIELD_RS2 = (24, 20)
  val FIELD_RS3 = (31, 27)
  val FIELD_F3 = (14, 12)
  val FIELD_FINE = (24, 20)
  val FIELD_PRIVATE = (11, 7)
  val FIELD_IMM12 = (31, 20)
  val FIELD_IMM20 = (31, 12)
  val FIELD_SHAMTW = (24, 20)
  val FIELD_CSR = (31, 20)
  val FIELD_RM = (14, 12)
  val FIELD_AQ = (26, 26)
  val FIELD_RL = (25, 25)
  val MATCH_FMADD = 0x00000004L
  val MASK_FMADD = 0x0600007fL
  val OPCODE_FMADD = 0x04
  val FUNCT2_FMADD = 0x00
  val MATCH_TEX = 0x04000004L
  val MASK_TEX = 0x0600007fL
  val OPCODE_TEX = 0x04
  val FUNCT2_TEX = 0x02
  val MATCH_TEXA = 0x06000004L
  val MASK_TEXA = 0x06000fffL
  val OPCODE_TEXA = 0x04
  val FUNCT2_TEXA = 0x03
  val MATCH_FADD = 0x00000000L
  val MASK_FADD = 0xfe00007fL
  val OPCODE_FADD = 0x00
  val FUNCT7_FADD = 0x00
  val MATCH_FMUL = 0x08000000L
  val MASK_FMUL = 0xfe00007fL
  val OPCODE_FMUL = 0x00
  val FUNCT7_FMUL = 0x04
  val MATCH_FNEG = 0x0c000000L
  val MASK_FNEG = 0xfff0007fL
  val OPCODE_FNEG = 0x00
  val FUNCT7_FNEG = 0x06
  val MATCH_FMOV = 0xa4000000L
  val MASK_FMOV = 0xfff0007fL
  val OPCODE_FMOV = 0x00
  val FUNCT7_FMOV = 0x52
  val MATCH_FSTEP = 0x10000000L
  val MASK_FSTEP = 0xfff0007fL
  val OPCODE_FSTEP = 0x00
  val FUNCT7_FSTEP = 0x08
  val MATCH_FRCP = 0x14000000L
  val MASK_FRCP = 0xfff0007fL
  val OPCODE_FRCP = 0x00
  val FUNCT7_FRCP = 0x0a
  val MATCH_IADD = 0x1c000000L
  val MASK_IADD = 0xfe00007fL
  val OPCODE_IADD = 0x00
  val FUNCT7_IADD = 0x0e
  val MATCH_ISHL = 0x20000000L
  val MASK_ISHL = 0xfe00007fL
  val OPCODE_ISHL = 0x00
  val FUNCT7_ISHL = 0x10
  val MATCH_ISHR = 0x24000000L
  val MASK_ISHR = 0xfe00007fL
  val OPCODE_ISHR = 0x00
  val FUNCT7_ISHR = 0x12
  val MATCH_IMUL = 0x28000000L
  val MASK_IMUL = 0xfe00007fL
  val OPCODE_IMUL = 0x00
  val FUNCT7_IMUL = 0x14
  val MATCH_ISUB = 0x60000000L
  val MASK_ISUB = 0xfe00007fL
  val OPCODE_ISUB = 0x00
  val FUNCT7_ISUB = 0x30
  val MATCH_IAND = 0x64000000L
  val MASK_IAND = 0xfe00007fL
  val OPCODE_IAND = 0x00
  val FUNCT7_IAND = 0x32
  val MATCH_IOR = 0x68000000L
  val MASK_IOR = 0xfe00007fL
  val OPCODE_IOR = 0x00
  val FUNCT7_IOR = 0x34
  val MATCH_IXOR = 0x6c000000L
  val MASK_IXOR = 0xfe00007fL
  val OPCODE_IXOR = 0x00
  val FUNCT7_IXOR = 0x36
  val MATCH_ISLT = 0x70000000L
  val MASK_ISLT = 0xfe00007fL
  val OPCODE_ISLT = 0x00
  val FUNCT7_ISLT = 0x38
  val MATCH_ISEQ = 0x74000000L
  val MASK_ISEQ = 0xfe00007fL
  val OPCODE_ISEQ = 0x00
  val FUNCT7_ISEQ = 0x3a
  val MATCH_I2F = 0x2c000000L
  val MASK_I2F = 0xfff0007fL
  val OPCODE_I2F = 0x00
  val FUNCT7_I2F = 0x16
  val MATCH_F2I = 0x30000000L
  val MASK_F2I = 0xfff0007fL
  val OPCODE_F2I = 0x00
  val FUNCT7_F2I = 0x18
  val MATCH_F2H = 0xa8000000L
  val MASK_F2H = 0xfff0007fL
  val OPCODE_F2H = 0x00
  val FUNCT7_F2H = 0x54
  val MATCH_H2F = 0xac000000L
  val MASK_H2F = 0xfff0007fL
  val OPCODE_H2F = 0x00
  val FUNCT7_H2F = 0x56
  val MATCH_FRSQ = 0x34000000L
  val MASK_FRSQ = 0xfff0007fL
  val OPCODE_FRSQ = 0x00
  val FUNCT7_FRSQ = 0x1a
  val MATCH_FSRGB = 0x38000000L
  val MASK_FSRGB = 0xfff0007fL
  val OPCODE_FSRGB = 0x00
  val FUNCT7_FSRGB = 0x1c
  val MATCH_DDX = 0x3c000000L
  val MASK_DDX = 0xfe00007fL
  val OPCODE_DDX = 0x00
  val FUNCT7_DDX = 0x1e
  val MATCH_DDY = 0x40000000L
  val MASK_DDY = 0xfe00007fL
  val OPCODE_DDY = 0x00
  val FUNCT7_DDY = 0x20
  val MATCH_LOAD = 0x44000000L
  val MASK_LOAD = 0xfff0007fL
  val OPCODE_LOAD = 0x00
  val FUNCT7_LOAD = 0x22
  val MATCH_STORE = 0x48000000L
  val MASK_STORE = 0xfe00007fL
  val OPCODE_STORE = 0x00
  val FUNCT7_STORE = 0x24
  val MATCH_BRZ = 0x4c000000L
  val MASK_BRZ = 0xfe00007fL
  val OPCODE_BRZ = 0x00
  val FUNCT7_BRZ = 0x26
  val MATCH_BRNZ = 0x50000000L
  val MASK_BRNZ = 0xfe00007fL
  val OPCODE_BRNZ = 0x00
  val FUNCT7_BRNZ = 0x28
  val MATCH_EXPUSH = 0x54000000L
  val MASK_EXPUSH = 0xfff00fffL
  val OPCODE_EXPUSH = 0x00
  val FUNCT7_EXPUSH = 0x2a
  val MATCH_EXELSE = 0x58000000L
  val MASK_EXELSE = 0xffff8fffL
  val OPCODE_EXELSE = 0x00
  val FUNCT7_EXELSE = 0x2c
  val MATCH_EXPOP = 0x5c000000L
  val MASK_EXPOP = 0xffff8fffL
  val OPCODE_EXPOP = 0x00
  val FUNCT7_EXPOP = 0x2e
  val MATCH_BARRIER = 0x78000000L
  val MASK_BARRIER = 0xffff8fffL
  val OPCODE_BARRIER = 0x00
  val FUNCT7_BARRIER = 0x3c
  val MATCH_EXANY = 0x7c000000L
  val MASK_EXANY = 0xffff807fL
  val OPCODE_EXANY = 0x00
  val FUNCT7_EXANY = 0x3e
  val MATCH_ZTEST = 0x80000000L
  val MASK_ZTEST = 0xffff8fffL
  val OPCODE_ZTEST = 0x00
  val FUNCT7_ZTEST = 0x40
  val MATCH_SOUT = 0x84000000L
  val MASK_SOUT = 0xfe00007fL
  val OPCODE_SOUT = 0x00
  val FUNCT7_SOUT = 0x42
  val MATCH_FATTR = 0x88000000L
  val MASK_FATTR = 0xfe00707fL
  val OPCODE_FATTR = 0x00
  val FUNCT7_FATTR = 0x44
  val MATCH_SMASK = 0x8c000000L
  val MASK_SMASK = 0xffff807fL
  val OPCODE_SMASK = 0x00
  val FUNCT7_SMASK = 0x46
  val MATCH_ATTIDX = 0x90000000L
  val MASK_ATTIDX = 0xffff807fL
  val OPCODE_ATTIDX = 0x00
  val FUNCT7_ATTIDX = 0x48
  val MATCH_ISRL = 0x94000000L
  val MASK_ISRL = 0xfe00007fL
  val OPCODE_ISRL = 0x00
  val FUNCT7_ISRL = 0x4a
  val MATCH_ISLTU = 0x98000000L
  val MASK_ISLTU = 0xfe00007fL
  val OPCODE_ISLTU = 0x00
  val FUNCT7_ISLTU = 0x4c
  val MATCH_TLD = 0x9c000000L
  val MASK_TLD = 0xffff807fL
  val OPCODE_TLD = 0x00
  val FUNCT7_TLD = 0x4e
  val MATCH_JMP = 0xa0000000L
  val MASK_JMP = 0xfe00007fL
  val OPCODE_JMP = 0x00
  val FUNCT7_JMP = 0x50
  val MATCH_LUI = 0x00000037L
  val MASK_LUI = 0x0000007fL
  val OPCODE_LUI = 0x37
  val MATCH_AUIPC = 0x00000017L
  val MASK_AUIPC = 0x0000007fL
  val OPCODE_AUIPC = 0x17
  val MATCH_ADDI = 0x00000013L
  val MASK_ADDI = 0x0000707fL
  val OPCODE_ADDI = 0x13
  val MATCH_SLTI = 0x00002013L
  val MASK_SLTI = 0x0000707fL
  val OPCODE_SLTI = 0x13
  val MATCH_SLTIU = 0x00003013L
  val MASK_SLTIU = 0x0000707fL
  val OPCODE_SLTIU = 0x13
  val MATCH_XORI = 0x00004013L
  val MASK_XORI = 0x0000707fL
  val OPCODE_XORI = 0x13
  val MATCH_ORI = 0x00006013L
  val MASK_ORI = 0x0000707fL
  val OPCODE_ORI = 0x13
  val MATCH_ANDI = 0x00007013L
  val MASK_ANDI = 0x0000707fL
  val OPCODE_ANDI = 0x13
  val MATCH_SLLI = 0x00001013L
  val MASK_SLLI = 0xfe00707fL
  val OPCODE_SLLI = 0x13
  val FUNCT7_SLLI = 0x00
  val MATCH_SRLI = 0x00005013L
  val MASK_SRLI = 0xfe00707fL
  val OPCODE_SRLI = 0x13
  val FUNCT7_SRLI = 0x00
  val MATCH_SRAI = 0x40005013L
  val MASK_SRAI = 0xfe00707fL
  val OPCODE_SRAI = 0x13
  val FUNCT7_SRAI = 0x20
  val MATCH_ADD = 0x00000033L
  val MASK_ADD = 0xfe00707fL
  val OPCODE_ADD = 0x33
  val FUNCT7_ADD = 0x00
  val MATCH_SUB = 0x40000033L
  val MASK_SUB = 0xfe00707fL
  val OPCODE_SUB = 0x33
  val FUNCT7_SUB = 0x20
  val MATCH_SLL = 0x00001033L
  val MASK_SLL = 0xfe00707fL
  val OPCODE_SLL = 0x33
  val FUNCT7_SLL = 0x00
  val MATCH_SLT = 0x00002033L
  val MASK_SLT = 0xfe00707fL
  val OPCODE_SLT = 0x33
  val FUNCT7_SLT = 0x00
  val MATCH_SLTU = 0x00003033L
  val MASK_SLTU = 0xfe00707fL
  val OPCODE_SLTU = 0x33
  val FUNCT7_SLTU = 0x00
  val MATCH_XOR = 0x00004033L
  val MASK_XOR = 0xfe00707fL
  val OPCODE_XOR = 0x33
  val FUNCT7_XOR = 0x00
  val MATCH_SRL = 0x00005033L
  val MASK_SRL = 0xfe00707fL
  val OPCODE_SRL = 0x33
  val FUNCT7_SRL = 0x00
  val MATCH_SRA = 0x40005033L
  val MASK_SRA = 0xfe00707fL
  val OPCODE_SRA = 0x33
  val FUNCT7_SRA = 0x20
  val MATCH_OR = 0x00006033L
  val MASK_OR = 0xfe00707fL
  val OPCODE_OR = 0x33
  val FUNCT7_OR = 0x00
  val MATCH_AND = 0x00007033L
  val MASK_AND = 0xfe00707fL
  val OPCODE_AND = 0x33
  val FUNCT7_AND = 0x00
  val MATCH_LB = 0x00000003L
  val MASK_LB = 0x0000707fL
  val OPCODE_LB = 0x03
  val MATCH_LH = 0x00001003L
  val MASK_LH = 0x0000707fL
  val OPCODE_LH = 0x03
  val MATCH_LW = 0x00002003L
  val MASK_LW = 0x0000707fL
  val OPCODE_LW = 0x03
  val MATCH_LBU = 0x00004003L
  val MASK_LBU = 0x0000707fL
  val OPCODE_LBU = 0x03
  val MATCH_LHU = 0x00005003L
  val MASK_LHU = 0x0000707fL
  val OPCODE_LHU = 0x03
  val MATCH_SW = 0x00002023L
  val MASK_SW = 0x0000707fL
  val OPCODE_SW = 0x23
  val MATCH_JAL = 0x0000006fL
  val MASK_JAL = 0x0000007fL
  val OPCODE_JAL = 0x6f
  val MATCH_BEQ = 0x00000063L
  val MASK_BEQ = 0x0000707fL
  val OPCODE_BEQ = 0x63
  val MATCH_BNE = 0x00001063L
  val MASK_BNE = 0x0000707fL
  val OPCODE_BNE = 0x63
  val MATCH_BLT = 0x00004063L
  val MASK_BLT = 0x0000707fL
  val OPCODE_BLT = 0x63
  val MATCH_BGE = 0x00005063L
  val MASK_BGE = 0x0000707fL
  val OPCODE_BGE = 0x63
  val MATCH_BLTU = 0x00006063L
  val MASK_BLTU = 0x0000707fL
  val OPCODE_BLTU = 0x63
  val MATCH_BGEU = 0x00007063L
  val MASK_BGEU = 0x0000707fL
  val OPCODE_BGEU = 0x63
  val MATCH_MUL = 0x02000033L
  val MASK_MUL = 0xfe00707fL
  val OPCODE_MUL = 0x33
  val FUNCT7_MUL = 0x01
  val MATCH_MULH = 0x02001033L
  val MASK_MULH = 0xfe00707fL
  val OPCODE_MULH = 0x33
  val FUNCT7_MULH = 0x01
  val MATCH_MULHSU = 0x02002033L
  val MASK_MULHSU = 0xfe00707fL
  val OPCODE_MULHSU = 0x33
  val FUNCT7_MULHSU = 0x01
  val MATCH_MULHU = 0x02003033L
  val MASK_MULHU = 0xfe00707fL
  val OPCODE_MULHU = 0x33
  val FUNCT7_MULHU = 0x01
  val MATCH_CZERO_EQZ = 0x0e005033L
  val MASK_CZERO_EQZ = 0xfe00707fL
  val OPCODE_CZERO_EQZ = 0x33
  val FUNCT7_CZERO_EQZ = 0x07
  val MATCH_CZERO_NEZ = 0x0e007033L
  val MASK_CZERO_NEZ = 0xfe00707fL
  val OPCODE_CZERO_NEZ = 0x33
  val FUNCT7_CZERO_NEZ = 0x07
  val MATCH_ANDN = 0x40007033L
  val MASK_ANDN = 0xfe00707fL
  val OPCODE_ANDN = 0x33
  val FUNCT7_ANDN = 0x20
  val MATCH_ORN = 0x40006033L
  val MASK_ORN = 0xfe00707fL
  val OPCODE_ORN = 0x33
  val FUNCT7_ORN = 0x20
  val MATCH_XNOR = 0x40004033L
  val MASK_XNOR = 0xfe00707fL
  val OPCODE_XNOR = 0x33
  val FUNCT7_XNOR = 0x20
  val MATCH_MIN = 0x0a004033L
  val MASK_MIN = 0xfe00707fL
  val OPCODE_MIN = 0x33
  val FUNCT7_MIN = 0x05
  val MATCH_MINU = 0x0a005033L
  val MASK_MINU = 0xfe00707fL
  val OPCODE_MINU = 0x33
  val FUNCT7_MINU = 0x05
  val MATCH_MAX = 0x0a006033L
  val MASK_MAX = 0xfe00707fL
  val OPCODE_MAX = 0x33
  val FUNCT7_MAX = 0x05
  val MATCH_MAXU = 0x0a007033L
  val MASK_MAXU = 0xfe00707fL
  val OPCODE_MAXU = 0x33
  val FUNCT7_MAXU = 0x05
  val MATCH_ROL = 0x60001033L
  val MASK_ROL = 0xfe00707fL
  val OPCODE_ROL = 0x33
  val FUNCT7_ROL = 0x30
  val MATCH_ROR = 0x60005033L
  val MASK_ROR = 0xfe00707fL
  val OPCODE_ROR = 0x33
  val FUNCT7_ROR = 0x30
  val MATCH_RORI = 0x60005013L
  val MASK_RORI = 0xfe00707fL
  val OPCODE_RORI = 0x13
  val FUNCT7_RORI = 0x30
  val MATCH_CLZ = 0x60001013L
  val MASK_CLZ = 0xfff0707fL
  val OPCODE_CLZ = 0x13
  val MATCH_CTZ = 0x60101013L
  val MASK_CTZ = 0xfff0707fL
  val OPCODE_CTZ = 0x13
  val MATCH_CPOP = 0x60201013L
  val MASK_CPOP = 0xfff0707fL
  val OPCODE_CPOP = 0x13
  val MATCH_SEXT_B = 0x60401013L
  val MASK_SEXT_B = 0xfff0707fL
  val OPCODE_SEXT_B = 0x13
  val MATCH_SEXT_H = 0x60501013L
  val MASK_SEXT_H = 0xfff0707fL
  val OPCODE_SEXT_H = 0x13
  val MATCH_ZEXT_H = 0x08004033L
  val MASK_ZEXT_H = 0xfff0707fL
  val OPCODE_ZEXT_H = 0x33
  val FUNCT7_ZEXT_H = 0x04
  val MATCH_REV8 = 0x69805013L
  val MASK_REV8 = 0xfff0707fL
  val OPCODE_REV8 = 0x13
  val MATCH_FADD_S = 0x00000053L
  val MASK_FADD_S = 0xfe00007fL
  val OPCODE_FADD_S = 0x53
  val FUNCT7_FADD_S = 0x00
  val FUNCT2_FADD_S = 0x00
  val MATCH_FSUB_S = 0x08000053L
  val MASK_FSUB_S = 0xfe00007fL
  val OPCODE_FSUB_S = 0x53
  val FUNCT7_FSUB_S = 0x04
  val FUNCT2_FSUB_S = 0x00
  val MATCH_FMUL_S = 0x10000053L
  val MASK_FMUL_S = 0xfe00007fL
  val OPCODE_FMUL_S = 0x53
  val FUNCT7_FMUL_S = 0x08
  val FUNCT2_FMUL_S = 0x00
  val MATCH_FSGNJ_S = 0x20000053L
  val MASK_FSGNJ_S = 0xfe00707fL
  val OPCODE_FSGNJ_S = 0x53
  val FUNCT7_FSGNJ_S = 0x10
  val FUNCT2_FSGNJ_S = 0x00
  val MATCH_FSGNJN_S = 0x20001053L
  val MASK_FSGNJN_S = 0xfe00707fL
  val OPCODE_FSGNJN_S = 0x53
  val FUNCT7_FSGNJN_S = 0x10
  val FUNCT2_FSGNJN_S = 0x00
  val MATCH_FSGNJX_S = 0x20002053L
  val MASK_FSGNJX_S = 0xfe00707fL
  val OPCODE_FSGNJX_S = 0x53
  val FUNCT7_FSGNJX_S = 0x10
  val FUNCT2_FSGNJX_S = 0x00
  val MATCH_FMIN_S = 0x28000053L
  val MASK_FMIN_S = 0xfe00707fL
  val OPCODE_FMIN_S = 0x53
  val FUNCT7_FMIN_S = 0x14
  val FUNCT2_FMIN_S = 0x00
  val MATCH_FMAX_S = 0x28001053L
  val MASK_FMAX_S = 0xfe00707fL
  val OPCODE_FMAX_S = 0x53
  val FUNCT7_FMAX_S = 0x14
  val FUNCT2_FMAX_S = 0x00
  val MATCH_FEQ_S = 0xa0002053L
  val MASK_FEQ_S = 0xfe00707fL
  val OPCODE_FEQ_S = 0x53
  val FUNCT7_FEQ_S = 0x50
  val FUNCT2_FEQ_S = 0x00
  val MATCH_FLT_S = 0xa0001053L
  val MASK_FLT_S = 0xfe00707fL
  val OPCODE_FLT_S = 0x53
  val FUNCT7_FLT_S = 0x50
  val FUNCT2_FLT_S = 0x00
  val MATCH_FLE_S = 0xa0000053L
  val MASK_FLE_S = 0xfe00707fL
  val OPCODE_FLE_S = 0x53
  val FUNCT7_FLE_S = 0x50
  val FUNCT2_FLE_S = 0x00
  val MATCH_FCLASS_S = 0xe0001053L
  val MASK_FCLASS_S = 0xfff0707fL
  val OPCODE_FCLASS_S = 0x53
  val FUNCT7_FCLASS_S = 0x70
  val FUNCT2_FCLASS_S = 0x00
  val MATCH_FCVT_W_S = 0xc0000053L
  val MASK_FCVT_W_S = 0xfff0007fL
  val OPCODE_FCVT_W_S = 0x53
  val FUNCT7_FCVT_W_S = 0x60
  val FUNCT2_FCVT_W_S = 0x00
  val MATCH_FCVT_WU_S = 0xc0100053L
  val MASK_FCVT_WU_S = 0xfff0007fL
  val OPCODE_FCVT_WU_S = 0x53
  val FUNCT7_FCVT_WU_S = 0x60
  val FUNCT2_FCVT_WU_S = 0x00
  val MATCH_FCVT_S_W = 0xd0000053L
  val MASK_FCVT_S_W = 0xfff0007fL
  val OPCODE_FCVT_S_W = 0x53
  val FUNCT7_FCVT_S_W = 0x68
  val FUNCT2_FCVT_S_W = 0x00
  val MATCH_FCVT_S_WU = 0xd0100053L
  val MASK_FCVT_S_WU = 0xfff0007fL
  val OPCODE_FCVT_S_WU = 0x53
  val FUNCT7_FCVT_S_WU = 0x68
  val FUNCT2_FCVT_S_WU = 0x00
  val MATCH_FCVT_S_H = 0x40200053L
  val MASK_FCVT_S_H = 0xfff0007fL
  val OPCODE_FCVT_S_H = 0x53
  val FUNCT7_FCVT_S_H = 0x20
  val MATCH_FCVT_H_S = 0x44000053L
  val MASK_FCVT_H_S = 0xfff0007fL
  val OPCODE_FCVT_H_S = 0x53
  val FUNCT7_FCVT_H_S = 0x22
  val MATCH_FMADD_S = 0x00000043L
  val MASK_FMADD_S = 0x0600007fL
  val OPCODE_FMADD_S = 0x43
  val FUNCT2_FMADD_S = 0x00
  val MATCH_FMSUB_S = 0x00000047L
  val MASK_FMSUB_S = 0x0600007fL
  val OPCODE_FMSUB_S = 0x47
  val FUNCT2_FMSUB_S = 0x00
  val MATCH_FNMSUB_S = 0x0000004bL
  val MASK_FNMSUB_S = 0x0600007fL
  val OPCODE_FNMSUB_S = 0x4b
  val FUNCT2_FNMSUB_S = 0x00
  val MATCH_FNMADD_S = 0x0000004fL
  val MASK_FNMADD_S = 0x0600007fL
  val OPCODE_FNMADD_S = 0x4f
  val FUNCT2_FNMADD_S = 0x00
  val MATCH_AMOADD_W = 0x0000202fL
  val MASK_AMOADD_W = 0xf800707fL
  val OPCODE_AMOADD_W = 0x2f
  val MATCH_AMOSWAP_W = 0x0800202fL
  val MASK_AMOSWAP_W = 0xf800707fL
  val OPCODE_AMOSWAP_W = 0x2f
  val MATCH_AMOXOR_W = 0x2000202fL
  val MASK_AMOXOR_W = 0xf800707fL
  val OPCODE_AMOXOR_W = 0x2f
  val MATCH_AMOAND_W = 0x6000202fL
  val MASK_AMOAND_W = 0xf800707fL
  val OPCODE_AMOAND_W = 0x2f
  val MATCH_AMOOR_W = 0x4000202fL
  val MASK_AMOOR_W = 0xf800707fL
  val OPCODE_AMOOR_W = 0x2f
  val MATCH_AMOMIN_W = 0x8000202fL
  val MASK_AMOMIN_W = 0xf800707fL
  val OPCODE_AMOMIN_W = 0x2f
  val MATCH_AMOMAX_W = 0xa000202fL
  val MASK_AMOMAX_W = 0xf800707fL
  val OPCODE_AMOMAX_W = 0x2f
  val MATCH_AMOMINU_W = 0xc000202fL
  val MASK_AMOMINU_W = 0xf800707fL
  val OPCODE_AMOMINU_W = 0x2f
  val MATCH_AMOMAXU_W = 0xe000202fL
  val MASK_AMOMAXU_W = 0xf800707fL
  val OPCODE_AMOMAXU_W = 0x2f
  val MATCH_AMOCAS_W = 0x2800202fL
  val MASK_AMOCAS_W = 0xf800707fL
  val OPCODE_AMOCAS_W = 0x2f
  val MATCH_TEXD = 0x0000002bL
  val MASK_TEXD = 0x0600707fL
  val OPCODE_TEXD = 0x2b
  val FUNCT2_TEXD = 0x00
  val MATCH_TEXAD = 0x0200002bL
  val MASK_TEXAD = 0x06007fffL
  val OPCODE_TEXAD = 0x2b
  val FUNCT2_TEXAD = 0x01
  val MATCH_TEXS = 0x0000102bL
  val MASK_TEXS = 0xfe007fffL
  val OPCODE_TEXS = 0x2b
  val FUNCT7_TEXS = 0x00
  val MATCH_FRCP_V2 = 0x0000000bL
  val MASK_FRCP_V2 = 0xfff0707fL
  val OPCODE_FRCP_V2 = 0x0b
  val FUNCT7_FRCP_V2 = 0x00
  val MATCH_FRSQ_V2 = 0x0200000bL
  val MASK_FRSQ_V2 = 0xfff0707fL
  val OPCODE_FRSQ_V2 = 0x0b
  val FUNCT7_FRSQ_V2 = 0x01
  val MATCH_FEXP2 = 0x0400000bL
  val MASK_FEXP2 = 0xfff0707fL
  val OPCODE_FEXP2 = 0x0b
  val FUNCT7_FEXP2 = 0x02
  val MATCH_FLOG2 = 0x0600000bL
  val MASK_FLOG2 = 0xfff0707fL
  val OPCODE_FLOG2 = 0x0b
  val FUNCT7_FLOG2 = 0x03
  val MATCH_DDX_V2 = 0x0000100bL
  val MASK_DDX_V2 = 0xfe00707fL
  val OPCODE_DDX_V2 = 0x0b
  val FUNCT7_DDX_V2 = 0x00
  val MATCH_DDY_V2 = 0x0200100bL
  val MASK_DDY_V2 = 0xfe00707fL
  val OPCODE_DDY_V2 = 0x0b
  val FUNCT7_DDY_V2 = 0x01
  val MATCH_TLD_V2 = 0x0000202bL
  val MASK_TLD_V2 = 0xfe0ff07fL
  val OPCODE_TLD_V2 = 0x2b
  val FUNCT7_TLD_V2 = 0x00
  val MATCH_ZTEST_V2 = 0x0000302bL
  val MASK_ZTEST_V2 = 0xffffffffL
  val OPCODE_ZTEST_V2 = 0x2b
  val FUNCT7_ZTEST_V2 = 0x00
  val MATCH_SOUT_V2 = 0x0000005bL
  val MASK_SOUT_V2 = 0xfe00707fL
  val OPCODE_SOUT_V2 = 0x5b
  val FUNCT7_SOUT_V2 = 0x00
  val MATCH_FATTR_V2 = 0x0000105bL
  val MASK_FATTR_V2 = 0xfe00707fL
  val OPCODE_FATTR_V2 = 0x5b
  val FUNCT7_FATTR_V2 = 0x00
  val MATCH_EXPUSH_V2 = 0x0000007bL
  val MASK_EXPUSH_V2 = 0xfff07fffL
  val OPCODE_EXPUSH_V2 = 0x7b
  val FUNCT7_EXPUSH_V2 = 0x00
  val MATCH_EXELSE_V2 = 0x0200007bL
  val MASK_EXELSE_V2 = 0xffffffffL
  val OPCODE_EXELSE_V2 = 0x7b
  val FUNCT7_EXELSE_V2 = 0x01
  val MATCH_EXPOP_V2 = 0x0400007bL
  val MASK_EXPOP_V2 = 0xffffffffL
  val OPCODE_EXPOP_V2 = 0x7b
  val FUNCT7_EXPOP_V2 = 0x02
  val MATCH_EXANY_V2 = 0x0600007bL
  val MASK_EXANY_V2 = 0xfffff07fL
  val OPCODE_EXANY_V2 = 0x7b
  val FUNCT7_EXANY_V2 = 0x03
  val MATCH_BARRIER_V2 = 0x0000107bL
  val MASK_BARRIER_V2 = 0xffffffffL
  val OPCODE_BARRIER_V2 = 0x7b
  val FUNCT7_BARRIER_V2 = 0x00
  val MATCH_CSRRW = 0x00001073L
  val MASK_CSRRW = 0x0000707fL
  val OPCODE_CSRRW = 0x73
  val MATCH_CSRRS = 0x00002073L
  val MASK_CSRRS = 0x0000707fL
  val OPCODE_CSRRS = 0x73
  val HALT = 0x00000000L

  def FMADD(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FMADD | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27) | ((f3.toLong & 0x7L) << 12))
  def TEX(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_TEX | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27) | ((f3.toLong & 0x7L) << 12))
  def TEXA(rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_TEXA | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27) | ((f3.toLong & 0x7L) << 12))
  def FADD(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FADD | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def FMUL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FMUL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def FNEG(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FNEG | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def FMOV(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FMOV | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def FSTEP(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FSTEP | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def FRCP(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FRCP | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def IADD(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_IADD | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def ISHL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ISHL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def ISHR(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ISHR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def IMUL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_IMUL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def ISUB(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ISUB | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def IAND(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_IAND | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def IOR(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_IOR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def IXOR(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_IXOR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def ISLT(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ISLT | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def ISEQ(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ISEQ | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def I2F(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_I2F | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def F2I(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_F2I | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def F2H(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_F2H | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def H2F(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_H2F | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def FRSQ(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FRSQ | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def FSRGB(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_FSRGB | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def DDX(rd: Int = 0, rs1: Int = 0, fine: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_DDX | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((fine.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def DDY(rd: Int = 0, rs1: Int = 0, fine: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_DDY | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((fine.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def LOAD(rd: Int = 0, rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_LOAD | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def STORE(rs1: Int = 0, rs2: Int = 0, `private`: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_STORE | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((`private`.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def BRZ(rs1: Int = 0, offset: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_BRZ | ((rs1.toLong & 0x1fL) << 15) | (((offset.toLong >> 5) & 0x1fL) << 20) | ((offset.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def BRNZ(rs1: Int = 0, offset: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_BRNZ | ((rs1.toLong & 0x1fL) << 15) | (((offset.toLong >> 5) & 0x1fL) << 20) | ((offset.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def EXPUSH(rs1: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_EXPUSH | ((rs1.toLong & 0x1fL) << 15) | ((f3.toLong & 0x7L) << 12))
  def EXELSE(f3: Int = 0): BigInt = BigInt(MATCH_EXELSE | ((f3.toLong & 0x7L) << 12))
  def EXPOP(f3: Int = 0): BigInt = BigInt(MATCH_EXPOP | ((f3.toLong & 0x7L) << 12))
  def BARRIER(f3: Int = 0): BigInt = BigInt(MATCH_BARRIER | ((f3.toLong & 0x7L) << 12))
  def EXANY(rd: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_EXANY | ((rd.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def ZTEST(f3: Int = 0): BigInt = BigInt(MATCH_ZTEST | ((f3.toLong & 0x7L) << 12))
  def SOUT(rs2: Int = 0, index: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_SOUT | ((rs2.toLong & 0x1fL) << 20) | (((index.toLong >> 5) & 0x1fL) << 15) | ((index.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def FATTR(rd: Int = 0, aindex: Int = 0): BigInt = BigInt(MATCH_FATTR | ((rd.toLong & 0x1fL) << 7) | (((aindex.toLong >> 5) & 0x1fL) << 20) | ((aindex.toLong & 0x1fL) << 15))
  def SMASK(rd: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_SMASK | ((rd.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def ATTIDX(rd: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ATTIDX | ((rd.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def ISRL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ISRL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def ISLTU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_ISLTU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((f3.toLong & 0x7L) << 12))
  def TLD(rd: Int = 0, f3: Int = 0): BigInt = BigInt(MATCH_TLD | ((rd.toLong & 0x1fL) << 7) | ((f3.toLong & 0x7L) << 12))
  def JMP(joffset: Int = 0): BigInt = BigInt(MATCH_JMP | (((joffset.toLong >> 15) & 0x7L) << 12) | (((joffset.toLong >> 10) & 0x1fL) << 20) | (((joffset.toLong >> 5) & 0x1fL) << 15) | ((joffset.toLong & 0x1fL) << 7))
  def LUI(rd: Int = 0, imm20: Int = 0): BigInt = BigInt(MATCH_LUI | ((rd.toLong & 0x1fL) << 7) | ((imm20.toLong & 0xfffffL) << 12))
  def AUIPC(rd: Int = 0, imm20: Int = 0): BigInt = BigInt(MATCH_AUIPC | ((rd.toLong & 0x1fL) << 7) | ((imm20.toLong & 0xfffffL) << 12))
  def ADDI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_ADDI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SLTI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_SLTI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SLTIU(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_SLTIU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def XORI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_XORI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def ORI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_ORI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def ANDI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_ANDI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SLLI(rd: Int = 0, rs1: Int = 0, shamtw: Int = 0): BigInt = BigInt(MATCH_SLLI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtw.toLong & 0x1fL) << 20))
  def SRLI(rd: Int = 0, rs1: Int = 0, shamtw: Int = 0): BigInt = BigInt(MATCH_SRLI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtw.toLong & 0x1fL) << 20))
  def SRAI(rd: Int = 0, rs1: Int = 0, shamtw: Int = 0): BigInt = BigInt(MATCH_SRAI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtw.toLong & 0x1fL) << 20))
  def ADD(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_ADD | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SUB(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SUB | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SLL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SLL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SLT(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SLT | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SLTU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SLTU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def XOR(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_XOR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SRL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SRL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SRA(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SRA | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def OR(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_OR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def AND(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_AND | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def LB(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LB | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LH(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LH | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LW(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LBU(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LBU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LHU(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LHU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SW(simm: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SW | (((simm.toLong >> 5) & 0x7fL) << 25) | ((simm.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def JAL(rd: Int = 0, joff: Int = 0): BigInt = BigInt(MATCH_JAL | ((rd.toLong & 0x1fL) << 7) | (((joff.toLong >> 20) & 0x1L) << 31) | (((joff.toLong >> 1) & 0x3ffL) << 21) | (((joff.toLong >> 11) & 0x1L) << 20) | (((joff.toLong >> 12) & 0xffL) << 12))
  def BEQ(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BEQ | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BNE(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BNE | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BLT(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BLT | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BGE(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BGE | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BLTU(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BLTU | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BGEU(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BGEU | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MUL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MUL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MULH(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MULH | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MULHSU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MULHSU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MULHU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MULHU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def CZERO_EQZ(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_CZERO_EQZ | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def CZERO_NEZ(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_CZERO_NEZ | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def ANDN(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_ANDN | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def ORN(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_ORN | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def XNOR(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_XNOR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MIN(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MIN | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MINU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MINU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MAX(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MAX | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MAXU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MAXU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def ROL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_ROL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def ROR(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_ROR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def RORI(rd: Int = 0, rs1: Int = 0, shamtw: Int = 0): BigInt = BigInt(MATCH_RORI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtw.toLong & 0x1fL) << 20))
  def CLZ(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_CLZ | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def CTZ(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_CTZ | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def CPOP(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_CPOP | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def SEXT_B(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_SEXT_B | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def SEXT_H(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_SEXT_H | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def ZEXT_H(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_ZEXT_H | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def REV8(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_REV8 | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def FADD_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FADD_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rm.toLong & 0x7L) << 12))
  def FSUB_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FSUB_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rm.toLong & 0x7L) << 12))
  def FMUL_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FMUL_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rm.toLong & 0x7L) << 12))
  def FSGNJ_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FSGNJ_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FSGNJN_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FSGNJN_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FSGNJX_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FSGNJX_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FMIN_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FMIN_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FMAX_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FMAX_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FEQ_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FEQ_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FLT_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FLT_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FLE_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_FLE_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FCLASS_S(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_FCLASS_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def FCVT_W_S(rd: Int = 0, rs1: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FCVT_W_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rm.toLong & 0x7L) << 12))
  def FCVT_WU_S(rd: Int = 0, rs1: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FCVT_WU_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rm.toLong & 0x7L) << 12))
  def FCVT_S_W(rd: Int = 0, rs1: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FCVT_S_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rm.toLong & 0x7L) << 12))
  def FCVT_S_WU(rd: Int = 0, rs1: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FCVT_S_WU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rm.toLong & 0x7L) << 12))
  def FCVT_S_H(rd: Int = 0, rs1: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FCVT_S_H | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rm.toLong & 0x7L) << 12))
  def FCVT_H_S(rd: Int = 0, rs1: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FCVT_H_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rm.toLong & 0x7L) << 12))
  def FMADD_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FMADD_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27) | ((rm.toLong & 0x7L) << 12))
  def FMSUB_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FMSUB_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27) | ((rm.toLong & 0x7L) << 12))
  def FNMSUB_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FNMSUB_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27) | ((rm.toLong & 0x7L) << 12))
  def FNMADD_S(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0, rm: Int = 0): BigInt = BigInt(MATCH_FNMADD_S | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27) | ((rm.toLong & 0x7L) << 12))
  def AMOADD_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOADD_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOSWAP_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOSWAP_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOXOR_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOXOR_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOAND_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOAND_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOOR_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOOR_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMIN_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMIN_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMAX_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMAX_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMINU_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMINU_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMAXU_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMAXU_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOCAS_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOCAS_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def TEXD(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, rs3: Int = 0): BigInt = BigInt(MATCH_TEXD | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27))
  def TEXAD(rs1: Int = 0, rs2: Int = 0, rs3: Int = 0): BigInt = BigInt(MATCH_TEXAD | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((rs3.toLong & 0x1fL) << 27))
  def TEXS(rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_TEXS | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def FRCP_V2(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_FRCP_V2 | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def FRSQ_V2(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_FRSQ_V2 | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def FEXP2(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_FEXP2 | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def FLOG2(rd: Int = 0, rs1: Int = 0): BigInt = BigInt(MATCH_FLOG2 | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15))
  def DDX_V2(rd: Int = 0, rs1: Int = 0, fine: Int = 0): BigInt = BigInt(MATCH_DDX_V2 | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((fine.toLong & 0x1fL) << 20))
  def DDY_V2(rd: Int = 0, rs1: Int = 0, fine: Int = 0): BigInt = BigInt(MATCH_DDY_V2 | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((fine.toLong & 0x1fL) << 20))
  def TLD_V2(rd: Int = 0, fine: Int = 0): BigInt = BigInt(MATCH_TLD_V2 | ((rd.toLong & 0x1fL) << 7) | ((fine.toLong & 0x1fL) << 20))
  def ZTEST_V2(): BigInt = BigInt(MATCH_ZTEST_V2)
  def SOUT_V2(rs2: Int = 0, index: Int = 0): BigInt = BigInt(MATCH_SOUT_V2 | ((rs2.toLong & 0x1fL) << 20) | (((index.toLong >> 5) & 0x1fL) << 15) | ((index.toLong & 0x1fL) << 7))
  def FATTR_V2(rd: Int = 0, aindex: Int = 0): BigInt = BigInt(MATCH_FATTR_V2 | ((rd.toLong & 0x1fL) << 7) | (((aindex.toLong >> 5) & 0x1fL) << 20) | ((aindex.toLong & 0x1fL) << 15))
  def EXPUSH_V2(rs1: Int = 0): BigInt = BigInt(MATCH_EXPUSH_V2 | ((rs1.toLong & 0x1fL) << 15))
  def EXELSE_V2(): BigInt = BigInt(MATCH_EXELSE_V2)
  def EXPOP_V2(): BigInt = BigInt(MATCH_EXPOP_V2)
  def EXANY_V2(rd: Int = 0): BigInt = BigInt(MATCH_EXANY_V2 | ((rd.toLong & 0x1fL) << 7))
  def BARRIER_V2(): BigInt = BigInt(MATCH_BARRIER_V2)
  def CSRRW(rd: Int = 0, rs1: Int = 0, csr: Int = 0): BigInt = BigInt(MATCH_CSRRW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((csr.toLong & 0xfffL) << 20))
  def CSRRS(rd: Int = 0, rs1: Int = 0, csr: Int = 0): BigInt = BigInt(MATCH_CSRRS | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((csr.toLong & 0xfffL) << 20))
}
