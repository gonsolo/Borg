// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
// GENERATED from hardware/hutt/isa by scripts/isa_gen.py; do not edit.

package hutt

object HuttOpcodes {
  val FIELD_RD = (11, 7)
  val FIELD_RS1 = (19, 15)
  val FIELD_RS2 = (24, 20)
  val FIELD_IMM12 = (31, 20)
  val FIELD_IMM20 = (31, 12)
  val FIELD_SHAMTD = (25, 20)
  val FIELD_SHAMTW = (24, 20)
  val FIELD_CSR = (31, 20)
  val FIELD_ZIMM5 = (19, 15)
  val FIELD_FM = (31, 28)
  val FIELD_PRED = (27, 24)
  val FIELD_SUCC = (23, 20)
  val FIELD_AQ = (26, 26)
  val FIELD_RL = (25, 25)
  val MATCH_LR_D = 0x1000302fL
  val MASK_LR_D = 0xf9f0707fL
  val OPCODE_LR_D = 0x2f
  val FUNCT3_LR_D = 0x03
  val FUNCT5_LR_D = 0x02
  val MATCH_SC_D = 0x1800302fL
  val MASK_SC_D = 0xf800707fL
  val OPCODE_SC_D = 0x2f
  val FUNCT3_SC_D = 0x03
  val FUNCT5_SC_D = 0x03
  val MATCH_AMOSWAP_D = 0x0800302fL
  val MASK_AMOSWAP_D = 0xf800707fL
  val OPCODE_AMOSWAP_D = 0x2f
  val FUNCT3_AMOSWAP_D = 0x03
  val FUNCT5_AMOSWAP_D = 0x01
  val MATCH_AMOADD_D = 0x0000302fL
  val MASK_AMOADD_D = 0xf800707fL
  val OPCODE_AMOADD_D = 0x2f
  val FUNCT3_AMOADD_D = 0x03
  val FUNCT5_AMOADD_D = 0x00
  val MATCH_AMOXOR_D = 0x2000302fL
  val MASK_AMOXOR_D = 0xf800707fL
  val OPCODE_AMOXOR_D = 0x2f
  val FUNCT3_AMOXOR_D = 0x03
  val FUNCT5_AMOXOR_D = 0x04
  val MATCH_AMOAND_D = 0x6000302fL
  val MASK_AMOAND_D = 0xf800707fL
  val OPCODE_AMOAND_D = 0x2f
  val FUNCT3_AMOAND_D = 0x03
  val FUNCT5_AMOAND_D = 0x0c
  val MATCH_AMOOR_D = 0x4000302fL
  val MASK_AMOOR_D = 0xf800707fL
  val OPCODE_AMOOR_D = 0x2f
  val FUNCT3_AMOOR_D = 0x03
  val FUNCT5_AMOOR_D = 0x08
  val MATCH_AMOMIN_D = 0x8000302fL
  val MASK_AMOMIN_D = 0xf800707fL
  val OPCODE_AMOMIN_D = 0x2f
  val FUNCT3_AMOMIN_D = 0x03
  val FUNCT5_AMOMIN_D = 0x10
  val MATCH_AMOMAX_D = 0xa000302fL
  val MASK_AMOMAX_D = 0xf800707fL
  val OPCODE_AMOMAX_D = 0x2f
  val FUNCT3_AMOMAX_D = 0x03
  val FUNCT5_AMOMAX_D = 0x14
  val MATCH_AMOMINU_D = 0xc000302fL
  val MASK_AMOMINU_D = 0xf800707fL
  val OPCODE_AMOMINU_D = 0x2f
  val FUNCT3_AMOMINU_D = 0x03
  val FUNCT5_AMOMINU_D = 0x18
  val MATCH_AMOMAXU_D = 0xe000302fL
  val MASK_AMOMAXU_D = 0xf800707fL
  val OPCODE_AMOMAXU_D = 0x2f
  val FUNCT3_AMOMAXU_D = 0x03
  val FUNCT5_AMOMAXU_D = 0x1c
  val MATCH_LWU = 0x00006003L
  val MASK_LWU = 0x0000707fL
  val OPCODE_LWU = 0x03
  val FUNCT3_LWU = 0x06
  val MATCH_LD = 0x00003003L
  val MASK_LD = 0x0000707fL
  val OPCODE_LD = 0x03
  val FUNCT3_LD = 0x03
  val MATCH_SD = 0x00003023L
  val MASK_SD = 0x0000707fL
  val OPCODE_SD = 0x23
  val FUNCT3_SD = 0x03
  val MATCH_SLLI = 0x00001013L
  val MASK_SLLI = 0xfc00707fL
  val OPCODE_SLLI = 0x13
  val FUNCT3_SLLI = 0x01
  val MATCH_SRLI = 0x00005013L
  val MASK_SRLI = 0xfc00707fL
  val OPCODE_SRLI = 0x13
  val FUNCT3_SRLI = 0x05
  val MATCH_SRAI = 0x40005013L
  val MASK_SRAI = 0xfc00707fL
  val OPCODE_SRAI = 0x13
  val FUNCT3_SRAI = 0x05
  val MATCH_ADDIW = 0x0000001bL
  val MASK_ADDIW = 0x0000707fL
  val OPCODE_ADDIW = 0x1b
  val FUNCT3_ADDIW = 0x00
  val MATCH_SLLIW = 0x0000101bL
  val MASK_SLLIW = 0xfe00707fL
  val OPCODE_SLLIW = 0x1b
  val FUNCT3_SLLIW = 0x01
  val FUNCT7_SLLIW = 0x00
  val MATCH_SRLIW = 0x0000501bL
  val MASK_SRLIW = 0xfe00707fL
  val OPCODE_SRLIW = 0x1b
  val FUNCT3_SRLIW = 0x05
  val FUNCT7_SRLIW = 0x00
  val MATCH_SRAIW = 0x4000501bL
  val MASK_SRAIW = 0xfe00707fL
  val OPCODE_SRAIW = 0x1b
  val FUNCT3_SRAIW = 0x05
  val FUNCT7_SRAIW = 0x20
  val MATCH_ADDW = 0x0000003bL
  val MASK_ADDW = 0xfe00707fL
  val OPCODE_ADDW = 0x3b
  val FUNCT3_ADDW = 0x00
  val FUNCT7_ADDW = 0x00
  val MATCH_SUBW = 0x4000003bL
  val MASK_SUBW = 0xfe00707fL
  val OPCODE_SUBW = 0x3b
  val FUNCT3_SUBW = 0x00
  val FUNCT7_SUBW = 0x20
  val MATCH_SLLW = 0x0000103bL
  val MASK_SLLW = 0xfe00707fL
  val OPCODE_SLLW = 0x3b
  val FUNCT3_SLLW = 0x01
  val FUNCT7_SLLW = 0x00
  val MATCH_SRLW = 0x0000503bL
  val MASK_SRLW = 0xfe00707fL
  val OPCODE_SRLW = 0x3b
  val FUNCT3_SRLW = 0x05
  val FUNCT7_SRLW = 0x00
  val MATCH_SRAW = 0x4000503bL
  val MASK_SRAW = 0xfe00707fL
  val OPCODE_SRAW = 0x3b
  val FUNCT3_SRAW = 0x05
  val FUNCT7_SRAW = 0x20
  val MATCH_MULW = 0x0200003bL
  val MASK_MULW = 0xfe00707fL
  val OPCODE_MULW = 0x3b
  val FUNCT3_MULW = 0x00
  val FUNCT7_MULW = 0x01
  val MATCH_DIVW = 0x0200403bL
  val MASK_DIVW = 0xfe00707fL
  val OPCODE_DIVW = 0x3b
  val FUNCT3_DIVW = 0x04
  val FUNCT7_DIVW = 0x01
  val MATCH_DIVUW = 0x0200503bL
  val MASK_DIVUW = 0xfe00707fL
  val OPCODE_DIVUW = 0x3b
  val FUNCT3_DIVUW = 0x05
  val FUNCT7_DIVUW = 0x01
  val MATCH_REMW = 0x0200603bL
  val MASK_REMW = 0xfe00707fL
  val OPCODE_REMW = 0x3b
  val FUNCT3_REMW = 0x06
  val FUNCT7_REMW = 0x01
  val MATCH_REMUW = 0x0200703bL
  val MASK_REMUW = 0xfe00707fL
  val OPCODE_REMUW = 0x3b
  val FUNCT3_REMUW = 0x07
  val FUNCT7_REMUW = 0x01
  val MATCH_LR_W = 0x1000202fL
  val MASK_LR_W = 0xf9f0707fL
  val OPCODE_LR_W = 0x2f
  val FUNCT3_LR_W = 0x02
  val FUNCT5_LR_W = 0x02
  val MATCH_SC_W = 0x1800202fL
  val MASK_SC_W = 0xf800707fL
  val OPCODE_SC_W = 0x2f
  val FUNCT3_SC_W = 0x02
  val FUNCT5_SC_W = 0x03
  val MATCH_AMOSWAP_W = 0x0800202fL
  val MASK_AMOSWAP_W = 0xf800707fL
  val OPCODE_AMOSWAP_W = 0x2f
  val FUNCT3_AMOSWAP_W = 0x02
  val FUNCT5_AMOSWAP_W = 0x01
  val MATCH_AMOADD_W = 0x0000202fL
  val MASK_AMOADD_W = 0xf800707fL
  val OPCODE_AMOADD_W = 0x2f
  val FUNCT3_AMOADD_W = 0x02
  val FUNCT5_AMOADD_W = 0x00
  val MATCH_AMOXOR_W = 0x2000202fL
  val MASK_AMOXOR_W = 0xf800707fL
  val OPCODE_AMOXOR_W = 0x2f
  val FUNCT3_AMOXOR_W = 0x02
  val FUNCT5_AMOXOR_W = 0x04
  val MATCH_AMOAND_W = 0x6000202fL
  val MASK_AMOAND_W = 0xf800707fL
  val OPCODE_AMOAND_W = 0x2f
  val FUNCT3_AMOAND_W = 0x02
  val FUNCT5_AMOAND_W = 0x0c
  val MATCH_AMOOR_W = 0x4000202fL
  val MASK_AMOOR_W = 0xf800707fL
  val OPCODE_AMOOR_W = 0x2f
  val FUNCT3_AMOOR_W = 0x02
  val FUNCT5_AMOOR_W = 0x08
  val MATCH_AMOMIN_W = 0x8000202fL
  val MASK_AMOMIN_W = 0xf800707fL
  val OPCODE_AMOMIN_W = 0x2f
  val FUNCT3_AMOMIN_W = 0x02
  val FUNCT5_AMOMIN_W = 0x10
  val MATCH_AMOMAX_W = 0xa000202fL
  val MASK_AMOMAX_W = 0xf800707fL
  val OPCODE_AMOMAX_W = 0x2f
  val FUNCT3_AMOMAX_W = 0x02
  val FUNCT5_AMOMAX_W = 0x14
  val MATCH_AMOMINU_W = 0xc000202fL
  val MASK_AMOMINU_W = 0xf800707fL
  val OPCODE_AMOMINU_W = 0x2f
  val FUNCT3_AMOMINU_W = 0x02
  val FUNCT5_AMOMINU_W = 0x18
  val MATCH_AMOMAXU_W = 0xe000202fL
  val MASK_AMOMAXU_W = 0xf800707fL
  val OPCODE_AMOMAXU_W = 0x2f
  val FUNCT3_AMOMAXU_W = 0x02
  val FUNCT5_AMOMAXU_W = 0x1c
  val MATCH_LUI = 0x00000037L
  val MASK_LUI = 0x0000007fL
  val OPCODE_LUI = 0x37
  val MATCH_AUIPC = 0x00000017L
  val MASK_AUIPC = 0x0000007fL
  val OPCODE_AUIPC = 0x17
  val MATCH_JAL = 0x0000006fL
  val MASK_JAL = 0x0000007fL
  val OPCODE_JAL = 0x6f
  val MATCH_JALR = 0x00000067L
  val MASK_JALR = 0x0000707fL
  val OPCODE_JALR = 0x67
  val FUNCT3_JALR = 0x00
  val MATCH_BEQ = 0x00000063L
  val MASK_BEQ = 0x0000707fL
  val OPCODE_BEQ = 0x63
  val FUNCT3_BEQ = 0x00
  val MATCH_BNE = 0x00001063L
  val MASK_BNE = 0x0000707fL
  val OPCODE_BNE = 0x63
  val FUNCT3_BNE = 0x01
  val MATCH_BLT = 0x00004063L
  val MASK_BLT = 0x0000707fL
  val OPCODE_BLT = 0x63
  val FUNCT3_BLT = 0x04
  val MATCH_BGE = 0x00005063L
  val MASK_BGE = 0x0000707fL
  val OPCODE_BGE = 0x63
  val FUNCT3_BGE = 0x05
  val MATCH_BLTU = 0x00006063L
  val MASK_BLTU = 0x0000707fL
  val OPCODE_BLTU = 0x63
  val FUNCT3_BLTU = 0x06
  val MATCH_BGEU = 0x00007063L
  val MASK_BGEU = 0x0000707fL
  val OPCODE_BGEU = 0x63
  val FUNCT3_BGEU = 0x07
  val MATCH_LB = 0x00000003L
  val MASK_LB = 0x0000707fL
  val OPCODE_LB = 0x03
  val FUNCT3_LB = 0x00
  val MATCH_LH = 0x00001003L
  val MASK_LH = 0x0000707fL
  val OPCODE_LH = 0x03
  val FUNCT3_LH = 0x01
  val MATCH_LW = 0x00002003L
  val MASK_LW = 0x0000707fL
  val OPCODE_LW = 0x03
  val FUNCT3_LW = 0x02
  val MATCH_LBU = 0x00004003L
  val MASK_LBU = 0x0000707fL
  val OPCODE_LBU = 0x03
  val FUNCT3_LBU = 0x04
  val MATCH_LHU = 0x00005003L
  val MASK_LHU = 0x0000707fL
  val OPCODE_LHU = 0x03
  val FUNCT3_LHU = 0x05
  val MATCH_SB = 0x00000023L
  val MASK_SB = 0x0000707fL
  val OPCODE_SB = 0x23
  val FUNCT3_SB = 0x00
  val MATCH_SH = 0x00001023L
  val MASK_SH = 0x0000707fL
  val OPCODE_SH = 0x23
  val FUNCT3_SH = 0x01
  val MATCH_SW = 0x00002023L
  val MASK_SW = 0x0000707fL
  val OPCODE_SW = 0x23
  val FUNCT3_SW = 0x02
  val MATCH_ADDI = 0x00000013L
  val MASK_ADDI = 0x0000707fL
  val OPCODE_ADDI = 0x13
  val FUNCT3_ADDI = 0x00
  val MATCH_SLTI = 0x00002013L
  val MASK_SLTI = 0x0000707fL
  val OPCODE_SLTI = 0x13
  val FUNCT3_SLTI = 0x02
  val MATCH_SLTIU = 0x00003013L
  val MASK_SLTIU = 0x0000707fL
  val OPCODE_SLTIU = 0x13
  val FUNCT3_SLTIU = 0x03
  val MATCH_XORI = 0x00004013L
  val MASK_XORI = 0x0000707fL
  val OPCODE_XORI = 0x13
  val FUNCT3_XORI = 0x04
  val MATCH_ORI = 0x00006013L
  val MASK_ORI = 0x0000707fL
  val OPCODE_ORI = 0x13
  val FUNCT3_ORI = 0x06
  val MATCH_ANDI = 0x00007013L
  val MASK_ANDI = 0x0000707fL
  val OPCODE_ANDI = 0x13
  val FUNCT3_ANDI = 0x07
  val MATCH_ADD = 0x00000033L
  val MASK_ADD = 0xfe00707fL
  val OPCODE_ADD = 0x33
  val FUNCT3_ADD = 0x00
  val FUNCT7_ADD = 0x00
  val MATCH_SUB = 0x40000033L
  val MASK_SUB = 0xfe00707fL
  val OPCODE_SUB = 0x33
  val FUNCT3_SUB = 0x00
  val FUNCT7_SUB = 0x20
  val MATCH_SLL = 0x00001033L
  val MASK_SLL = 0xfe00707fL
  val OPCODE_SLL = 0x33
  val FUNCT3_SLL = 0x01
  val FUNCT7_SLL = 0x00
  val MATCH_SLT = 0x00002033L
  val MASK_SLT = 0xfe00707fL
  val OPCODE_SLT = 0x33
  val FUNCT3_SLT = 0x02
  val FUNCT7_SLT = 0x00
  val MATCH_SLTU = 0x00003033L
  val MASK_SLTU = 0xfe00707fL
  val OPCODE_SLTU = 0x33
  val FUNCT3_SLTU = 0x03
  val FUNCT7_SLTU = 0x00
  val MATCH_XOR = 0x00004033L
  val MASK_XOR = 0xfe00707fL
  val OPCODE_XOR = 0x33
  val FUNCT3_XOR = 0x04
  val FUNCT7_XOR = 0x00
  val MATCH_SRL = 0x00005033L
  val MASK_SRL = 0xfe00707fL
  val OPCODE_SRL = 0x33
  val FUNCT3_SRL = 0x05
  val FUNCT7_SRL = 0x00
  val MATCH_SRA = 0x40005033L
  val MASK_SRA = 0xfe00707fL
  val OPCODE_SRA = 0x33
  val FUNCT3_SRA = 0x05
  val FUNCT7_SRA = 0x20
  val MATCH_OR = 0x00006033L
  val MASK_OR = 0xfe00707fL
  val OPCODE_OR = 0x33
  val FUNCT3_OR = 0x06
  val FUNCT7_OR = 0x00
  val MATCH_AND = 0x00007033L
  val MASK_AND = 0xfe00707fL
  val OPCODE_AND = 0x33
  val FUNCT3_AND = 0x07
  val FUNCT7_AND = 0x00
  val MATCH_FENCE = 0x0000000fL
  val MASK_FENCE = 0x0000707fL
  val OPCODE_FENCE = 0x0f
  val FUNCT3_FENCE = 0x00
  val MATCH_ECALL = 0x00000073L
  val MASK_ECALL = 0xffffffffL
  val OPCODE_ECALL = 0x73
  val FUNCT12_ECALL = 0x00
  val MATCH_EBREAK = 0x00100073L
  val MASK_EBREAK = 0xffffffffL
  val OPCODE_EBREAK = 0x73
  val FUNCT12_EBREAK = 0x01
  val MATCH_MUL = 0x02000033L
  val MASK_MUL = 0xfe00707fL
  val OPCODE_MUL = 0x33
  val FUNCT3_MUL = 0x00
  val FUNCT7_MUL = 0x01
  val MATCH_MULH = 0x02001033L
  val MASK_MULH = 0xfe00707fL
  val OPCODE_MULH = 0x33
  val FUNCT3_MULH = 0x01
  val FUNCT7_MULH = 0x01
  val MATCH_MULHSU = 0x02002033L
  val MASK_MULHSU = 0xfe00707fL
  val OPCODE_MULHSU = 0x33
  val FUNCT3_MULHSU = 0x02
  val FUNCT7_MULHSU = 0x01
  val MATCH_MULHU = 0x02003033L
  val MASK_MULHU = 0xfe00707fL
  val OPCODE_MULHU = 0x33
  val FUNCT3_MULHU = 0x03
  val FUNCT7_MULHU = 0x01
  val MATCH_DIV = 0x02004033L
  val MASK_DIV = 0xfe00707fL
  val OPCODE_DIV = 0x33
  val FUNCT3_DIV = 0x04
  val FUNCT7_DIV = 0x01
  val MATCH_DIVU = 0x02005033L
  val MASK_DIVU = 0xfe00707fL
  val OPCODE_DIVU = 0x33
  val FUNCT3_DIVU = 0x05
  val FUNCT7_DIVU = 0x01
  val MATCH_REM = 0x02006033L
  val MASK_REM = 0xfe00707fL
  val OPCODE_REM = 0x33
  val FUNCT3_REM = 0x06
  val FUNCT7_REM = 0x01
  val MATCH_REMU = 0x02007033L
  val MASK_REMU = 0xfe00707fL
  val OPCODE_REMU = 0x33
  val FUNCT3_REMU = 0x07
  val FUNCT7_REMU = 0x01
  val MATCH_SFENCE_VMA = 0x12000073L
  val MASK_SFENCE_VMA = 0xfe007fffL
  val OPCODE_SFENCE_VMA = 0x73
  val FUNCT3_SFENCE_VMA = 0x00
  val FUNCT7_SFENCE_VMA = 0x09
  val MATCH_SRET = 0x10200073L
  val MASK_SRET = 0xffffffffL
  val OPCODE_SRET = 0x73
  val FUNCT3_SRET = 0x00
  val FUNCT12_SRET = 0x102
  val MATCH_MRET = 0x30200073L
  val MASK_MRET = 0xffffffffL
  val OPCODE_MRET = 0x73
  val FUNCT3_MRET = 0x00
  val FUNCT12_MRET = 0x302
  val MATCH_WFI = 0x10500073L
  val MASK_WFI = 0xffffffffL
  val OPCODE_WFI = 0x73
  val FUNCT3_WFI = 0x00
  val FUNCT12_WFI = 0x105
  val MATCH_CSRRW = 0x00001073L
  val MASK_CSRRW = 0x0000707fL
  val OPCODE_CSRRW = 0x73
  val FUNCT3_CSRRW = 0x01
  val MATCH_CSRRS = 0x00002073L
  val MASK_CSRRS = 0x0000707fL
  val OPCODE_CSRRS = 0x73
  val FUNCT3_CSRRS = 0x02
  val MATCH_CSRRC = 0x00003073L
  val MASK_CSRRC = 0x0000707fL
  val OPCODE_CSRRC = 0x73
  val FUNCT3_CSRRC = 0x03
  val MATCH_CSRRWI = 0x00005073L
  val MASK_CSRRWI = 0x0000707fL
  val OPCODE_CSRRWI = 0x73
  val FUNCT3_CSRRWI = 0x05
  val MATCH_CSRRSI = 0x00006073L
  val MASK_CSRRSI = 0x0000707fL
  val OPCODE_CSRRSI = 0x73
  val FUNCT3_CSRRSI = 0x06
  val MATCH_CSRRCI = 0x00007073L
  val MASK_CSRRCI = 0x0000707fL
  val OPCODE_CSRRCI = 0x73
  val FUNCT3_CSRRCI = 0x07
  val MATCH_FENCE_I = 0x0000100fL
  val MASK_FENCE_I = 0x0000707fL
  val OPCODE_FENCE_I = 0x0f
  val FUNCT3_FENCE_I = 0x01
  val PAUSE = 0x0100000fL
  val SCALL = 0x00000073L
  val SBREAK = 0x00100073L
  val NOP = 0x00000013L
  val RET = 0x00008067L

  def LR_D(rd: Int = 0, rs1: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_LR_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def SC_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_SC_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOSWAP_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOSWAP_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOADD_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOADD_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOXOR_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOXOR_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOAND_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOAND_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOOR_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOOR_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMIN_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMIN_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMAX_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMAX_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMINU_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMINU_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMAXU_D(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMAXU_D | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def LWU(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LWU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LD(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LD | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SD(simm: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SD | (((simm.toLong >> 5) & 0x7fL) << 25) | ((simm.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SLLI(rd: Int = 0, rs1: Int = 0, shamtd: Int = 0): BigInt = BigInt(MATCH_SLLI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtd.toLong & 0x3fL) << 20))
  def SRLI(rd: Int = 0, rs1: Int = 0, shamtd: Int = 0): BigInt = BigInt(MATCH_SRLI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtd.toLong & 0x3fL) << 20))
  def SRAI(rd: Int = 0, rs1: Int = 0, shamtd: Int = 0): BigInt = BigInt(MATCH_SRAI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtd.toLong & 0x3fL) << 20))
  def ADDIW(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_ADDIW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SLLIW(rd: Int = 0, rs1: Int = 0, shamtw: Int = 0): BigInt = BigInt(MATCH_SLLIW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtw.toLong & 0x1fL) << 20))
  def SRLIW(rd: Int = 0, rs1: Int = 0, shamtw: Int = 0): BigInt = BigInt(MATCH_SRLIW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtw.toLong & 0x1fL) << 20))
  def SRAIW(rd: Int = 0, rs1: Int = 0, shamtw: Int = 0): BigInt = BigInt(MATCH_SRAIW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((shamtw.toLong & 0x1fL) << 20))
  def ADDW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_ADDW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SUBW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SUBW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SLLW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SLLW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SRLW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SRLW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SRAW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SRAW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MULW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MULW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def DIVW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_DIVW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def DIVUW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_DIVUW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def REMW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_REMW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def REMUW(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_REMUW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def LR_W(rd: Int = 0, rs1: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_LR_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def SC_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_SC_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOSWAP_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOSWAP_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOADD_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOADD_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOXOR_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOXOR_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOAND_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOAND_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOOR_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOOR_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMIN_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMIN_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMAX_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMAX_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMINU_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMINU_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def AMOMAXU_W(rd: Int = 0, rs1: Int = 0, rs2: Int = 0, aq: Int = 0, rl: Int = 0): BigInt = BigInt(MATCH_AMOMAXU_W | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20) | ((aq.toLong & 0x1L) << 26) | ((rl.toLong & 0x1L) << 25))
  def LUI(rd: Int = 0, imm20: Int = 0): BigInt = BigInt(MATCH_LUI | ((rd.toLong & 0x1fL) << 7) | ((imm20.toLong & 0xfffffL) << 12))
  def AUIPC(rd: Int = 0, imm20: Int = 0): BigInt = BigInt(MATCH_AUIPC | ((rd.toLong & 0x1fL) << 7) | ((imm20.toLong & 0xfffffL) << 12))
  def JAL(rd: Int = 0, joff: Int = 0): BigInt = BigInt(MATCH_JAL | ((rd.toLong & 0x1fL) << 7) | (((joff.toLong >> 20) & 0x1L) << 31) | (((joff.toLong >> 1) & 0x3ffL) << 21) | (((joff.toLong >> 11) & 0x1L) << 20) | (((joff.toLong >> 12) & 0xffL) << 12))
  def JALR(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_JALR | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def BEQ(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BEQ | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BNE(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BNE | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BLT(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BLT | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BGE(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BGE | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BLTU(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BLTU | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def BGEU(boff: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_BGEU | (((boff.toLong >> 12) & 0x1L) << 31) | (((boff.toLong >> 5) & 0x3fL) << 25) | (((boff.toLong >> 1) & 0xfL) << 8) | (((boff.toLong >> 11) & 0x1L) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def LB(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LB | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LH(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LH | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LW(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LBU(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LBU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def LHU(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_LHU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SB(simm: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SB | (((simm.toLong >> 5) & 0x7fL) << 25) | ((simm.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SH(simm: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SH | (((simm.toLong >> 5) & 0x7fL) << 25) | ((simm.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SW(simm: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SW | (((simm.toLong >> 5) & 0x7fL) << 25) | ((simm.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def ADDI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_ADDI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SLTI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_SLTI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def SLTIU(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_SLTIU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def XORI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_XORI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def ORI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_ORI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
  def ANDI(rd: Int = 0, rs1: Int = 0, imm12: Int = 0): BigInt = BigInt(MATCH_ANDI | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((imm12.toLong & 0xfffL) << 20))
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
  def FENCE(fm: Int = 0, pred: Int = 0, succ: Int = 0, rs1: Int = 0, rd: Int = 0): BigInt = BigInt(MATCH_FENCE | ((fm.toLong & 0xfL) << 28) | ((pred.toLong & 0xfL) << 24) | ((succ.toLong & 0xfL) << 20) | ((rs1.toLong & 0x1fL) << 15) | ((rd.toLong & 0x1fL) << 7))
  def ECALL(): BigInt = BigInt(MATCH_ECALL)
  def EBREAK(): BigInt = BigInt(MATCH_EBREAK)
  def MUL(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MUL | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MULH(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MULH | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MULHSU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MULHSU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def MULHU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_MULHU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def DIV(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_DIV | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def DIVU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_DIVU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def REM(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_REM | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def REMU(rd: Int = 0, rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_REMU | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SFENCE_VMA(rs1: Int = 0, rs2: Int = 0): BigInt = BigInt(MATCH_SFENCE_VMA | ((rs1.toLong & 0x1fL) << 15) | ((rs2.toLong & 0x1fL) << 20))
  def SRET(): BigInt = BigInt(MATCH_SRET)
  def MRET(): BigInt = BigInt(MATCH_MRET)
  def WFI(): BigInt = BigInt(MATCH_WFI)
  def CSRRW(rd: Int = 0, rs1: Int = 0, csr: Int = 0): BigInt = BigInt(MATCH_CSRRW | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((csr.toLong & 0xfffL) << 20))
  def CSRRS(rd: Int = 0, rs1: Int = 0, csr: Int = 0): BigInt = BigInt(MATCH_CSRRS | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((csr.toLong & 0xfffL) << 20))
  def CSRRC(rd: Int = 0, rs1: Int = 0, csr: Int = 0): BigInt = BigInt(MATCH_CSRRC | ((rd.toLong & 0x1fL) << 7) | ((rs1.toLong & 0x1fL) << 15) | ((csr.toLong & 0xfffL) << 20))
  def CSRRWI(rd: Int = 0, csr: Int = 0, zimm5: Int = 0): BigInt = BigInt(MATCH_CSRRWI | ((rd.toLong & 0x1fL) << 7) | ((csr.toLong & 0xfffL) << 20) | ((zimm5.toLong & 0x1fL) << 15))
  def CSRRSI(rd: Int = 0, csr: Int = 0, zimm5: Int = 0): BigInt = BigInt(MATCH_CSRRSI | ((rd.toLong & 0x1fL) << 7) | ((csr.toLong & 0xfffL) << 20) | ((zimm5.toLong & 0x1fL) << 15))
  def CSRRCI(rd: Int = 0, csr: Int = 0, zimm5: Int = 0): BigInt = BigInt(MATCH_CSRRCI | ((rd.toLong & 0x1fL) << 7) | ((csr.toLong & 0xfffL) << 20) | ((zimm5.toLong & 0x1fL) << 15))
  def FENCE_I(imm12: Int = 0, rs1: Int = 0, rd: Int = 0): BigInt = BigInt(MATCH_FENCE_I | ((imm12.toLong & 0xfffL) << 20) | ((rs1.toLong & 0x1fL) << 15) | ((rd.toLong & 0x1fL) << 7))
}
