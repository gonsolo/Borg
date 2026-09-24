// SPDX-FileCopyrightText: © 2026
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.UInt

/** Instruction specification for the Borg FP16 shader processor.
  * This acts as the single source of truth for both hardware decoding
  * and firmware/Python instruction generation.
  */
object Instructions {

  case class BitField(hi: Int, lo: Int) {
    def apply(u: UInt): UInt = u(hi, lo)
  }

  // @doc:isa-bitfields
  // --- RISC-V Instruction Format Definitions ---
  val BF_RS1    = BitField(19, 15)
  val BF_RS2    = BitField(24, 20)
  val BF_RS3    = BitField(31, 27)
  val BF_RD     = BitField(11, 7)
  val BF_OP     = BitField(6, 0)
  val BF_FUNCT3 = BitField(14, 12)
  val BF_FUNCT7 = BitField(31, 25)

  // Custom hardware decode boundaries. The funct7 sub-op uses the full 7 bits
  // (31:25); the original FP ops only set bits 28:25 (so widening this is
  // backward compatible), while the integer ops below use the upper bits too.
  val BF_F7_OP = BitField(31, 25)
  val BITS_OPCODE_FMA_BIT = 2

  // --- Opcodes ---
  val OPCODE_ALU = 0x00
  val OPCODE_FMA = 0x04

  val FUNCT7_ADD   = 0x00
  val FUNCT7_MUL   = 0x04
  val FUNCT7_FNEG  = 0x06
  val FUNCT7_FSTEP = 0x08
  val FUNCT7_FRCP  = 0x0A
  // FTEX moved to the R4-type shape (see FUNCT2_FTEX below) to gain rs3 as a
  // texture-slot index for multi-texture binding -- 0x0C is retired, not
  // reused: any stale binary encoding a pre-migration FTEX must not silently
  // decode as something else.
  // Integer ops (16-bit, operate on the raw register bits). Use the widened
  // 7-bit funct7 field; the upper bits keep them distinct from the FP ops.
  val FUNCT7_IADD  = 0x0E  // rd = rs1 + rs2        (16-bit wrap)
  val FUNCT7_ISHL  = 0x10  // rd = rs1 << (rs2 & 15)
  val FUNCT7_ISHR  = 0x12  // rd = rs1 >> (rs2 & 15) (arithmetic)
  val FUNCT7_IMUL  = 0x14  // rd = (rs1 * rs2)[15:0]
  val FUNCT7_I2F   = 0x16  // rd = fp16(signed int16 rs1)  (unary)
  val FUNCT7_F2I   = 0x18  // rd = signed int16(fp16 rs1)  (unary, truncate)
  val FUNCT7_FRSQ  = 0x1A  // rd = 1/sqrt(rs1)             (unary, LUT)
  val FUNCT7_FSRGB = 0x1C  // rd = linearToSrgb(rs1)       (unary, LUT)
  val FUNCT7_DDX   = 0x1E  // rd = dFdx(rs1)  (cross-lane: lane1 - lane0)
  val FUNCT7_DDY   = 0x20  // rd = dFdy(rs1)  (cross-lane: lane2 - lane0)
  // Memory access. The FIRST instructions that touch an address the shader
  // computes itself -- every op above reaches memory only through a
  // fixed-function path (FTEX's texture fetch, the uniform bank's
  // funct3-selected read, the hardware ABI's tile-buffer write).
  //
  // The operand is a WORD INDEX, not a byte address: at FP16 a register holds
  // 16 bits and the address space is 25, so a register simply cannot carry a
  // full address. The effective address is `LS_BASE + (rs1 << 2)`, which is
  // the same base-plus-index shape the texture unit already uses, and maps
  // directly onto a Vulkan SSBO binding -- LS_BASE is the descriptor, the
  // shader supplies the element index.
  val FUNCT7_LOAD  = 0x22  // rd = mem32[LS_BASE + (rs1 << 2)]
  val FUNCT7_STORE = 0x24  // mem32[LS_BASE + (rs1 << 2)] = rs2   (no rd)
  // Control flow. Until these, every shader was straight-line: the program
  // counter only ever advanced by one.
  //
  // The branch target is an ABSOLUTE word index into instruction memory,
  // packed into the otherwise-unused rs2 and rd fields as (rs2 << 5) | rd.
  // 10 bits reaches 1023, far past any IMEM Borg builds (56-72 words), and
  // absolute is easier for a compiler to emit than PC-relative when the
  // whole program is a handful of words.
  //
  // The condition tests the RAW register bits against zero, so FP16 -0.0
  // (0x8000) counts as non-zero -- the same convention the discard register
  // already uses (`data =/= 0`).
  val FUNCT7_BRZ   = 0x26  // if (rs1 == 0) pc = target
  val FUNCT7_BRNZ  = 0x28  // if (rs1 != 0) pc = target
  // Execution mask -- divergent control flow for the 2x2 quad.
  //
  // BRZ/BRNZ redirect the single shared program counter, so they can only
  // express control flow whose condition is the same in every lane. These
  // three express the other case, and they do it WITHOUT branching: both
  // sides of an `if` execute, and lanes that should not be running are
  // masked off so their writes (registers, memory, fragment outputs) do not
  // happen. That is predication, and for a 2x2 quad it is cheaper and far
  // simpler than a per-lane program counter.
  //
  //   EXPUSH rs1 : push exec; exec &= (rs1 != 0), per lane
  //   EXELSE     : exec = enclosing & ~exec      (the else arm)
  //   EXPOP      : exec = pop()                  (end of the if)
  val FUNCT7_EXPUSH = 0x2A
  val FUNCT7_EXELSE = 0x2C
  val FUNCT7_EXPOP  = 0x2E
  // Integer ALU completeness: the datapath above only ever grew IADD/ISHL/
  // ISHR/IMUL, so subtraction, bitwise logic, and comparison were the only
  // ops an integer-heavy shader (index math, texture-coordinate masks,
  // flow-control conditions) could not express without going through FP.
  val FUNCT7_ISUB  = 0x30  // rd = rs1 - rs2         (16-bit wrap)
  val FUNCT7_IAND  = 0x32  // rd = rs1 & rs2
  val FUNCT7_IOR   = 0x34  // rd = rs1 | rs2
  val FUNCT7_IXOR  = 0x36  // rd = rs1 ^ rs2
  // Comparisons produce a 0/1 result (not a raw flag) so they compose with
  // the existing BRZ/BRNZ/EXPUSH ops, which all test "raw bits == 0". A
  // full set of six relational ops is synthesizable from just these two by
  // swapping operands (for GT/LE) or testing the other branch polarity (for
  // NE) -- the same minimal basis RV32I itself uses (SLT/SLTU only).
  val FUNCT7_ISLT  = 0x38  // rd = (rs1 <s rs2) ? 1 : 0   (signed)
  val FUNCT7_ISEQ  = 0x3A  // rd = (rs1 == rs2) ? 1 : 0
  // Compute only (BorgConfig.computeEnabled). Stops the invocation like HALT
  // (running := false, zero latency), but records that it stopped AT a
  // barrier rather than finishing, plus the PC to resume at. BorgCore only
  // ever runs one quad at a time, so OpControlBarrier's "every invocation in
  // the workgroup reaches this point" is enforced by BorgComputeSequencer:
  // it runs every quad of the workgroup up to its own BARRIER before letting
  // any of them past it. See BorgComputeSequencer's doc for what happens if
  // quads disagree (SPIR-V requires uniform control flow through a barrier;
  // Borg makes a violation of that observable rather than silently wrong,
  // same as branch_divergent/exec_fault).
  val FUNCT7_BARRIER = 0x3C
  // Gated on hasControlFlow, same as EXPUSH/EXELSE/EXPOP (not computeEnabled:
  // nothing about it depends on the compute dispatch sequencer, only on the
  // execution mask, which fragment shaders have too). Reduces the CURRENT
  // execMask to a single raw 0/1 in rd -- "is any lane still active" --
  // exactly the piece BRZ/BRNZ/EXPUSH/EXELSE/EXPOP were missing for a
  // divergent LOOP: EXPUSH masks off a lane whose per-lane condition just
  // went false, and EXANY (fed to BRNZ) asks whether every lane has now
  // exited before deciding to loop back. Uniform loops (BRZ/BRNZ alone) and
  // divergent if/else (EXPUSH/EXELSE/EXPOP alone) already work without it.
  val FUNCT7_EXANY = 0x3E
  // Early per-fragment tests (SPIR-V EarlyFragmentTests, core Vulkan with no
  // feature bit). Borg's depth/stencil test otherwise runs AFTER the fragment
  // shader, so a fragment that fails it has already executed every STORE.
  // ZTEST runs the configured depth/stencil test for the quad at this point,
  // using the depth the shader has already written to r29, and performs the
  // depth/stencil writes then. Lanes that fail become helper invocations:
  // they keep executing (derivatives still need them) but their STOREs are
  // suppressed, and the end-of-shader write-back no longer re-tests -- it
  // writes colour to exactly the samples that passed here.
  //
  // The compiler emits it once, at top level, after the r29 write and before
  // the first STORE. Outside a rasterized fragment (MMIO or compute runs) it
  // completes immediately and does nothing.
  val FUNCT7_ZTEST = 0x40
  // The draw front end (BorgConfig.drawEnabled; docs/B1_geometry_front_end.md).
  // Both take a 10-bit IMMEDIATE component index, split over two register
  // fields the way RISC-V S-type stores split theirs: the index names a slot
  // in the triangle's record, and a compiler knows it statically.
  //
  // SOUT stores rs2 as output component `index` of this invocation. The
  // sequencer owns the address: for a vertex shader, component c of corner k
  // lands at outBase + 4*(3c + k), so a varying's three per-vertex values sit
  // side by side; for the setup ROM, at outBase + 4c. The index is packed
  // into rs1:rd, as (rs1 << 5) | rd. Masked lanes store nothing.
  val FUNCT7_SOUT  = 0x42
  // FATTR loads component `index` of the triangle being shaded: its three
  // per-vertex values into rd, rd+1, rd+2, in every active lane. A varying is
  // then l0*a0 + l1*a1 + l2*a2 with the raster ROM's perspective-correct
  // barycentrics. The index is packed into rs2:rs1, as (rs2 << 5) | rs1, like
  // a branch target. rd must leave room for all three (rd <= 29).
  val FUNCT7_FATTR = 0x44
  // SMASK rd: this lane's coverage mask -- the samples of the pixel the
  // triangle covers, after the pipeline's static sample mask -- as an
  // integer (gl_SampleMaskIn). With the edge planes in u0..u8 it also gives
  // the compiler centroid interpolation: evaluate the planes at the first
  // covered sample instead of the centre.
  val FUNCT7_SMASK = 0x46
  // ATTIDX rd: the colour attachment this pass of the tile renders (0..3).
  // With several attachments the tile is rendered once per attachment; the
  // fragment shader writes that attachment's colour to r24/r26..r28.
  val FUNCT7_ATTIDX = 0x48
  // The two RISC-V integer ops ISHR/ISLT left out: SRL (logical shift right)
  // and SLTU (unsigned less-than). Emulating them cost several instructions
  // each -- and every 16-bit index unpack or unsigned bounds check needs one.
  val FUNCT7_ISRL  = 0x4A  // rd = rs1 >>> (rs2 & 31)   (logical)
  val FUNCT7_ISLTU = 0x4C  // rd = (rs1 <u rs2) ? 1 : 0
  // @doc:end

  // R4-type sub-opcodes (opcode bit 2 set, discriminated by the 2-bit funct2
  // at instr[26:25] -- see encodeR4Type). FMADD is funct2=0 (unchanged).
  // FTEX moved here (funct2=1) specifically to gain rs3 as a texture-slot
  // index for multi-texture binding: under the ALU-opcode RType shape its
  // funct7 (bits 31:25) already used the full width BF_RS3 (bits 31:27)
  // would need, leaving no room for a 3rd operand. R4-type's much narrower
  // funct2 frees those bits. This is the same 3-source-operand shape real
  // RISC-V uses for its own fmadd.s -- U, V and a texture-select index are a
  // structural match for it, not a repurposing.
  //
  // rs3 (texSelect) is expected to be a compile-time-constant descriptor
  // binding index, pinned into a const GPR exactly like borgc's existing
  // push_const_reg mechanism -- not a per-invocation dynamic value.
  //
  // FTEX writes FOUR registers, rd..rd+3 = R, G, B, A -- a sampled image is a
  // vec4. rd must leave room for all four (rd <= 28).
  val FUNCT2_FMADD = 0
  val FUNCT2_FTEX  = 1
  // The descriptor-based texture unit (BorgSampler; docs/B2_texture_unit.md).
  // TEX rd, rs1 = u, rs2 = v, rs3 = register holding the control word (which
  // texture and sampler, which operation): RGBA to rd..rd+3 (rd <= 28).
  // TEXA rs1 = w or array layer, rs2 = LOD or bias, rs3 = depth reference:
  // this lane's extra sampling arguments, kept for the TEX that follows.
  val FUNCT2_TEX   = 2
  val FUNCT2_TEXA  = 3

  /** Split an absolute branch target into the rs2/rd fields it is packed into. */
  def branchTargetFields(target: Int): (Int, Int) = {
    require(target >= 0 && target < 1024, s"branch target out of range: $target")
    ((target >> 5) & 0x1f, target & 0x1f)
  }

  // --- Base Instruction Encoders ---
  // Long, not Int: funct7 values >= 0x40, and R4-type rs3 >= 16, set bit 31,
  // which overflows an Int shift into a negative literal.
  def encodeRType(funct7: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_ALU): BigInt =
    BigInt((funct7.toLong << BF_FUNCT7.lo) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  def encodeR4Type(rs3: Int, funct2: Int, rs2: Int, rs1: Int, rd: Int, funct3: Int = 0, opcode: Int = OPCODE_FMA): BigInt =
    BigInt((rs3.toLong << BF_RS3.lo) | (funct2 << 25) | (rs2 << BF_RS2.lo) | (rs1 << BF_RS1.lo) | (funct3 << BF_FUNCT3.lo) | (rd << BF_RD.lo) | (opcode << BF_OP.lo))

  // @doc:isa-encoders
  def ADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ADD, rs2, rs1, rd, funct3)
  def MUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_MUL, rs2, rs1, rd, funct3)
  def FNEG(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FNEG, 0, rs1, rd, funct3)
  def FSTEP(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FSTEP, 0, rs1, rd, funct3)
  def FRCP(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FRCP, 0, rs1, rd, funct3)
  /** rs3: a REGISTER INDEX (like FMA's own rs3), not an immediate -- the
    * hardware reads whatever value is stored in register rs3 and uses its
    * low bits to select which texture-binding slot to sample (see
    * FUNCT2_FTEX). A caller wanting texSelect=N must first write N into
    * some register and pass that register's index here -- exactly the
    * same "pin a constant into a register, then reference it" shape
    * borgc's push_const_reg already uses. Defaults to r0 for
    * single-texture callers (whose r0 need not even hold 0: any value
    * whose low log2(maxTextureBindings) bits are 0 selects slot 0). */
  def FTEX(rs1: Int, rs2: Int, rd: Int, rs3: Int = 0, funct3: Int = 0): BigInt =
    encodeR4Type(rs3, FUNCT2_FTEX, rs2, rs1, rd, funct3)
  def IADD(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IADD, rs2, rs1, rd, funct3)
  def ISHL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISHL, rs2, rs1, rd, funct3)
  def ISHR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISHR, rs2, rs1, rd, funct3)
  def IMUL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IMUL, rs2, rs1, rd, funct3)
  def ISUB(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISUB, rs2, rs1, rd, funct3)
  def IAND(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IAND, rs2, rs1, rd, funct3)
  def IOR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IOR, rs2, rs1, rd, funct3)
  def IXOR(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_IXOR, rs2, rs1, rd, funct3)
  def ISLT(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISLT, rs2, rs1, rd, funct3)
  def ISEQ(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISEQ, rs2, rs1, rd, funct3)
  def I2F(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_I2F, 0, rs1, rd, funct3)
  def F2I(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_F2I, 0, rs1, rd, funct3)
  def FRSQ(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FRSQ, 0, rs1, rd, funct3)
  def FSRGB(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_FSRGB, 0, rs1, rd, funct3)
  def DDX(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_DDX, 0, rs1, rd, funct3)
  def DDY(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_DDY, 0, rs1, rd, funct3)
  def LOAD(rs1: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_LOAD, 0, rs1, rd, funct3)
  /** STORE has no destination register; rd is encoded as 0. */
  def STORE(rs1: Int, rs2: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_STORE, rs2, rs1, 0, funct3)
  def BRZ(rs1: Int, target: Int, funct3: Int = 0): BigInt = {
    val (hi, lo) = branchTargetFields(target)
    encodeRType(FUNCT7_BRZ, hi, rs1, lo, funct3)
  }
  def EXPUSH(rs1: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXPUSH, 0, rs1, 0, funct3)
  def EXELSE(funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXELSE, 0, 0, 0, funct3)
  def EXPOP(funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXPOP, 0, 0, 0, funct3)
  def BARRIER(funct3: Int = 0): BigInt = encodeRType(FUNCT7_BARRIER, 0, 0, 0, funct3)
  def EXANY(rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_EXANY, 0, 0, rd, funct3)
  def ZTEST(funct3: Int = 0): BigInt = encodeRType(FUNCT7_ZTEST, 0, 0, 0, funct3)
  def BRNZ(rs1: Int, target: Int, funct3: Int = 0): BigInt = {
    val (hi, lo) = branchTargetFields(target)
    encodeRType(FUNCT7_BRNZ, hi, rs1, lo, funct3)
  }
  def FMA(rs1: Int, rs2: Int, rs3: Int, rd: Int, funct3: Int = 0): BigInt = encodeR4Type(rs3, 0, rs2, rs1, rd, funct3)
  /** TEX: sample; rs3 names the register holding the control word. */
  def TEX(rd: Int, rs1: Int, rs2: Int, rs3: Int, funct3: Int = 0): BigInt = {
    require(rd <= 28, s"TEX writes rd..rd+3, so rd <= 28: $rd")
    encodeR4Type(rs3, FUNCT2_TEX, rs2, rs1, rd, funct3)
  }
  /** TEXA: w/layer, LOD/bias and depth reference for the next TEX. */
  def TEXA(rs1: Int, rs2: Int, rs3: Int, funct3: Int = 0): BigInt = encodeR4Type(rs3, FUNCT2_TEXA, rs2, rs1, 0, funct3)
  def SMASK(rd: Int): BigInt = encodeRType(FUNCT7_SMASK, 0, 0, rd)
  def ATTIDX(rd: Int): BigInt = encodeRType(FUNCT7_ATTIDX, 0, 0, rd)
  def ISRL(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISRL, rs2, rs1, rd, funct3)
  def ISLTU(rs1: Int, rs2: Int, rd: Int, funct3: Int = 0): BigInt = encodeRType(FUNCT7_ISLTU, rs2, rs1, rd, funct3)
  /** SOUT: store rs2 as output component `index` (packed into rs1:rd). */
  def SOUT(rs2: Int, index: Int, funct3: Int = 0): BigInt = {
    val (hi, lo) = branchTargetFields(index)
    encodeRType(FUNCT7_SOUT, rs2, hi, lo, funct3)
  }
  /** FATTR: rd..rd+2 = component `index`'s per-vertex values (packed into rs2:rs1). */
  def FATTR(rd: Int, index: Int): BigInt = {
    require(rd <= 29, s"FATTR writes rd..rd+2, so rd <= 29: $rd")
    val (hi, lo) = branchTargetFields(index)
    encodeRType(FUNCT7_FATTR, hi, lo, rd)
  }
  // @doc:end

  /** Operand shape of an instruction, which decides its C macro signature. */
  sealed trait Shape
  case object RType  extends Shape  // rd, rs1, rs2
  case object R1Type extends Shape  // rd, rs1        (unary)
  case object R4Type extends Shape  // rd, rs1, rs2, rs3
  case object Store  extends Shape  // rs1, rs2       (no destination)
  case object Branch extends Shape  // rs1, target    (target packed into rs2:rd)
  case object Mask1  extends Shape  // rs1            (no destination)
  case object Mask0  extends Shape  // (no operands)
  case object MaskDest extends Shape  // rd           (no source operand)
  case object StoreIdx extends Shape  // rs2, index   (index packed into rs1:rd)
  case object LoadIdx  extends Shape  // rd, index    (index packed into rs2:rs1)

  /** THE instruction table. Everything downstream -- hardware decode, the C
    * header, any future Python emitter -- comes from here, so an opcode cannot
    * exist in one and not another.
    *
    * This table is why borg_isa.h is generated rather than written: as a
    * hand-maintained mirror it silently fell four opcodes behind (DDX, DDY,
    * FRSQ, FSRGB), which only surfaced when a test tried to validate a real
    * compiled shader against it. */
  val all: Seq[(String, Int, Shape)] = Seq(
    ("FADD",   FUNCT7_ADD,   RType),
    ("FMUL",   FUNCT7_MUL,   RType),
    ("FNEG",   FUNCT7_FNEG,  R1Type),
    ("FSTEP",  FUNCT7_FSTEP, R1Type),
    ("FRCP",   FUNCT7_FRCP,  R1Type),
    // FTEX is R4-type now (see FUNCT2_FTEX) -- not funct7-keyed, so like
    // FMADD it is special-cased in EmitIsaHeader rather than listed here.
    ("IADD",   FUNCT7_IADD,  RType),
    ("ISHL",   FUNCT7_ISHL,  RType),
    ("ISHR",   FUNCT7_ISHR,  RType),
    ("IMUL",   FUNCT7_IMUL,  RType),
    ("ISUB",   FUNCT7_ISUB,  RType),
    ("IAND",   FUNCT7_IAND,  RType),
    ("IOR",    FUNCT7_IOR,   RType),
    ("IXOR",   FUNCT7_IXOR,  RType),
    ("ISLT",   FUNCT7_ISLT,  RType),
    ("ISEQ",   FUNCT7_ISEQ,  RType),
    ("I2F",    FUNCT7_I2F,   R1Type),
    ("F2I",    FUNCT7_F2I,   R1Type),
    ("FRSQ",   FUNCT7_FRSQ,  R1Type),
    ("FSRGB",  FUNCT7_FSRGB, R1Type),
    ("DDX",    FUNCT7_DDX,   R1Type),
    ("DDY",    FUNCT7_DDY,   R1Type),
    ("LOAD",   FUNCT7_LOAD,  R1Type),
    ("STORE",  FUNCT7_STORE, Store),
    ("BRZ",    FUNCT7_BRZ,   Branch),
    ("BRNZ",   FUNCT7_BRNZ,  Branch),
    ("EXPUSH", FUNCT7_EXPUSH, Mask1),
    ("EXELSE", FUNCT7_EXELSE, Mask0),
    ("EXPOP",  FUNCT7_EXPOP,  Mask0),
    ("BARRIER", FUNCT7_BARRIER, Mask0),
    ("EXANY",  FUNCT7_EXANY,  MaskDest),
    ("ZTEST",  FUNCT7_ZTEST,  Mask0),
    ("SOUT",   FUNCT7_SOUT,   StoreIdx),
    ("FATTR",  FUNCT7_FATTR,  LoadIdx),
    ("SMASK",  FUNCT7_SMASK,  MaskDest),
    ("ATTIDX", FUNCT7_ATTIDX, MaskDest),
    ("ISRL",   FUNCT7_ISRL,   RType),
    ("ISLTU",  FUNCT7_ISLTU,  RType)
  )

  // --- String Formatters for C / Python Generation ---
  def PY_ARGS_R    = s"(funct3 << ${BF_FUNCT3.lo}) | (rs2 << ${BF_RS2.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"
  def PY_ARGS_R4   = s"(funct3 << ${BF_FUNCT3.lo}) | (rs3 << ${BF_RS3.lo}) | (rs2 << ${BF_RS2.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"
  def PY_ARGS_FNEG = s"(funct3 << ${BF_FUNCT3.lo}) | (rs1 << ${BF_RS1.lo}) | (rd << ${BF_RD.lo})"

  def C_ARGS_R     = s"((funct3) << ${BF_FUNCT3.lo}) | ((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
  def C_ARGS_R4    = s"((funct3) << ${BF_FUNCT3.lo}) | ((rs3) << ${BF_RS3.lo}) | ((rs2) << ${BF_RS2.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
  def C_ARGS_FNEG  = s"((funct3) << ${BF_FUNCT3.lo}) | ((rs1) << ${BF_RS1.lo}) | ((rd) << ${BF_RD.lo})"
}
