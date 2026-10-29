// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** Top-level IO for the Hutt CPU core.
  *
  * @param instrAddrWidth word-address width on the instruction fetch port
  * @param dataAddrWidth  byte-address width on the data port
  * @param xlen           XLEN: 32 = RV32I, 64 = RV64I
  */
class HuttIO(val instrAddrWidth: Int, val dataAddrWidth: Int, val xlen: Int = 32) extends Bundle {
  val instr     = new HuttInstrBus(instrAddrWidth)
  val data      = new HuttBus(dataAddrWidth, xlen)
  val interrupt = Input(Bool())  // CLINT machine timer (MTIP)
  val dbgPc     = Output(UInt(xlen.W))
  // Free-running cycle counter, also readable as the `time` CSR.
  val dbgCycleCounter = Output(UInt(xlen.W))
  // Trace taps: every TLB fill and every physical store.
  val dbgPtwFillSeq  = Output(UInt(32.W))
  val dbgPtwFillVA   = Output(UInt(xlen.W))
  val dbgPtwFillPPN  = Output(UInt(44.W))
  val dbgPtwFillLevel = Output(UInt(2.W))
  val dbgStoreSeq    = Output(UInt(32.W))
  val dbgStorePc     = Output(UInt(xlen.W))
  val dbgStorePhysAddr = Output(UInt(dataAddrWidth.W))
  val dbgStoreVA     = Output(UInt(xlen.W))
  val dbgStoreData   = Output(UInt(xlen.W))
  // Every write to x1 (ra) and x18 (s2), plus satp/privLevel.
  val dbgX1WriteSeq  = Output(UInt(32.W))
  val dbgX1WritePc   = Output(UInt(xlen.W))
  val dbgX1WriteVal  = Output(UInt(xlen.W))
  val dbgX18WriteSeq = Output(UInt(32.W))
  val dbgX18WritePc  = Output(UInt(xlen.W))
  val dbgX18WriteVal = Output(UInt(xlen.W))
  // Raw regfile write bus.
  val dbgRegWen   = Output(Bool())
  val dbgRegWAddr = Output(UInt(5.W))
  val dbgRegWData = Output(UInt(xlen.W))
  val dbgState    = Output(UInt(4.W))
  val dbgWbExecEn = Output(Bool())
  val dbgDRd      = Output(UInt(5.W))
  val dbgInstr    = Output(UInt(32.W))
  val dbgSatp        = Output(UInt(xlen.W))
  val dbgPrivLevel   = Output(UInt(2.W))
  // Every sepc write, from trap entry or an explicit `csrw sepc`.
  val dbgSepcWriteSeq = Output(UInt(32.W))
  val dbgSepcWritePc  = Output(UInt(xlen.W))
  val dbgSepcWriteVal = Output(UInt(xlen.W))
  // Every load that writes a destination register (rd != 0).
  val dbgLoadSeq      = Output(UInt(32.W))
  val dbgLoadPc       = Output(UInt(xlen.W))
  val dbgLoadVA       = Output(UInt(xlen.W))
  // Every sfence.vma.
  val dbgSfenceSeq    = Output(UInt(32.W))
  val dbgSfencePc     = Output(UInt(xlen.W))
  val dbgLoadPhysAddr = Output(UInt(xlen.W))
  val dbgLoadRd       = Output(UInt(5.W))
  val dbgLoadVal      = Output(UInt(xlen.W))
  // Selected GPR taps from HuttRegFile.
  val dbgS1 = Output(UInt(xlen.W))
  val dbgA5 = Output(UInt(xlen.W))
  val dbgS3 = Output(UInt(xlen.W))
  val dbgS5 = Output(UInt(xlen.W))
  val dbgS2 = Output(UInt(xlen.W))
  val dbgA0 = Output(UInt(xlen.W))
  val dbgRa = Output(UInt(xlen.W))
  // Latch of the last trap taken (timer, ecall, ebreak, illegal, page fault).
  val dbgTrapSeq    = Output(UInt(32.W))
  val dbgTrapFromPc = Output(UInt(xlen.W))
  val dbgTrapCause  = Output(UInt(xlen.W))
  val dbgTrapToPriv = Output(UInt(2.W))
  val dbgTrapMtvec  = Output(UInt(xlen.W))
  val dbgTrapStvec  = Output(UInt(xlen.W))
  val dbgTrapTargetPc = Output(UInt(xlen.W))
}

/** Hutt — a simple multi-cycle RV32I/RV64I CPU.
  *
  * Privilege levels: M-mode (reset) + S-mode.  No U-mode traps yet.
  *
  * M-mode CSRs: mstatus, mtvec, mscratch, mepc, mcause, mtval, mie, mip,
  *              medeleg, mideleg, mhartid, misa.
  * S-mode CSRs: sstatus (mstatus view), stvec, sscratch, sepc, scause, stval,
  *              sie (mie view), sip (mip view), satp.
  *
  * Traps: ECALL (cause 8/9/11 by privilege), EBREAK (cause 3), M/S timer IRQs,
  *        page faults (cause 12/13/15).
  * Returns: MRET restores from MPP; SRET restores from SPP.
  * Delegation: medeleg/mideleg route exceptions/interrupts to S-mode.
  *
  * MMU (xlen=64 only): Sv39, 3-level page table, 16-entry direct-mapped TLB.
  *   satp.MODE=8 enables translation in S/U mode.  SFENCE.VMA flushes TLB.
  *   TLB hits skip the permission re-check, so X-only pages may allow data access.
  *   No mstatus.MPRV.
  *
  * No compressed instructions.
  *
  * @param resetVector word-aligned byte address of the first instruction
  */
class Hutt(
    val instrAddrWidth: Int = 23,
    val dataAddrWidth: Int  = 28,
    val resetVector: BigInt = 0,
    val xlen: Int = 32,
    // Extra sCsrSel stage for CSR reads; ECP5 timing only, slow ASIC clocks set false.
    val pipelinedCsrRead: Boolean = true,
    // S-mode, its CSRs and medeleg/mideleg; bare-metal firmware never leaves M-mode.
    val hasSupervisorMode: Boolean = true,
    // Simulation-only trace registers (here and in HuttRegFile); never read by Hutt.
    val hasDebugPorts: Boolean = true
) extends Module {

  val io = IO(new HuttIO(instrAddrWidth, dataAddrWidth, xlen))

  val pc    = RegInit(resetVector.U(xlen.W))
  val instr = RegInit(0.U(32.W))

  io.dbgPc := pc

  val cycleCounter = RegInit(0.U(xlen.W))
  cycleCounter := cycleCounter + 1.U
  io.dbgCycleCounter := cycleCounter

  // Privilege level: 3=M, 1=S, 0=U, reset to M.
  val privLevel = RegInit(3.U(2.W))

  // mstatus: bit 1=SIE, 3=MIE, 5=SPIE, 7=MPIE, 8=SPP, 12:11=MPP.
  val mstatus  = RegInit(0.U(xlen.W))
  val mtvec    = RegInit(0.U(xlen.W))
  val mscratch = RegInit(0.U(xlen.W))
  val mepc     = RegInit(0.U(xlen.W))
  val mcause   = RegInit(0.U(xlen.W))
  val mtval    = RegInit(0.U(xlen.W))
  val mie      = RegInit(0.U(xlen.W))
  val medelegOpt: Option[UInt] = if (hasSupervisorMode) Some(RegInit(0.U(xlen.W))) else None
  val midelegOpt: Option[UInt] = if (hasSupervisorMode) Some(RegInit(0.U(xlen.W))) else None
  // mip: bit 7 MTIP is hardware (CLINT); bits 5/1 STIP/SSIP are software-set.
  val mip_sw  = RegInit(0.U(xlen.W))
  val mip     = Cat(0.U((xlen - 8).W), io.interrupt, 0.U(7.W)) | mip_sw
  // misa: MXL field + I + S extensions.
  val misaMxl = if (xlen == 64) 2.U(2.W) else 1.U(2.W)
  val misa    = Cat(misaMxl, 0.U((xlen - 28).W),
                    ((1 << ('I' - 'A')) | (1 << ('S' - 'A'))).U(26.W))

  // S-mode CSRs (Option: None when !hasSupervisorMode).
  def optReg(): Option[UInt] = if (hasSupervisorMode) Some(RegInit(0.U(xlen.W))) else None
  val stvecOpt    = optReg()
  val sscratchOpt = optReg()
  val sepcOpt     = optReg()
  val scauseOpt   = optReg()
  val stvalOpt    = optReg()
  // Reads as 0 when the register doesn't exist.
  def rd(o: Option[UInt]): UInt = o.getOrElse(0.U(xlen.W))
  def rdW(o: Option[UInt], w: Int): UInt = o.getOrElse(0.U(w.W))
  // A debug register, None when !hasDebugPorts.
  def dbgReg(w: Int = xlen): Option[UInt] =
    if (hasDebugPorts) Some(RegInit(0.U(w.W))) else None
  // satp: Sv39 address-translation CSR (0x180).  Meaningful only for xlen=64.
  //   MODE[63:60]=8 → Sv39; ASID[59:44]; PPN[43:0] of root page table.
  val satp     = RegInit(0.U(xlen.W))

  // Sv39 TLB (16-entry direct-mapped; xlen=64 only).
  val TLB_ENTRIES = 16
  val TLB_BITS    = 4   // log2(TLB_ENTRIES)
  val tlbValid = RegInit(VecInit(Seq.fill(TLB_ENTRIES)(false.B)))
  val tlbVPN   = Reg(Vec(TLB_ENTRIES, UInt(27.W)))  // VA[38:12]
  val tlbPPN   = Reg(Vec(TLB_ENTRIES, UInt(44.W)))  // PA page (4KB granularity)
  val tlbFlags = Reg(Vec(TLB_ENTRIES, UInt(8.W)))   // PTE[7:0]

  // Page-table walker state (xlen=64 only).
  val ptwVA      = Reg(UInt(xlen.W))   // virtual address being translated
  val ptwLevel   = Reg(UInt(2.W))      // PTW level (2=root, 0=leaf)
  val ptwA       = Reg(UInt(xlen.W))   // current page-table base (byte address)
  val ptwIsInstr = RegInit(false.B)    // true = instruction fetch, false = data
  val ptwIsWrite = RegInit(false.B)    // true = store (for fault cause)
  val dataPhysAddr = Reg(UInt(xlen.W)) // physical address for data access
  val dataVAReg    = Reg(UInt(xlen.W)) // debug: VA mirror of dataPhysAddr (plain load/store only)

  // A extension: LR/SC/AMO* (xlen=64 only).
  // Where the PTW continues after a data translation: 0=plain load/store (sMemReq),
  // 1=AMO/LR read phase (sAmoLoadReq), 2=AMO/SC write phase (sAmoStoreReq).
  val amoPending = RegInit(0.U(2.W))
  val amoOldVal  = Reg(UInt(xlen.W))   // value loaded in the read phase (old value for rd)
  val amoNewVal  = Reg(UInt(xlen.W))   // value to write in the store phase
  // Single-hart reservation: set by LR, checked and cleared by SC.
  val lrValid = RegInit(false.B)
  val lrAddr  = Reg(UInt(xlen.W))

  // mmuEnabled: Sv39 translation active (xlen=64, non-M-mode, MODE=8).
  val mmuEnabled: Bool =
    if (xlen == 64) (satp(63, 60) === 8.U && privLevel =/= 3.U) else false.B

  // sstatus: S-mode visible bits of mstatus (MXR=19, SUM=18, SPP=8, SPIE=5, SIE=1).
  val SSTATUS_MASK = 0xC0122L.U(xlen.W)
  // sie/sip: S-mode view of mie/mip (SEIE=9, STIE=5, SSIE=1).
  val SIE_SIP_MASK = 0x222L.U(xlen.W)
  // mip software-writable bits: STIP(5), SSIP(1).
  val MIP_SW_WR    = 0x22L.U(xlen.W)

  val regFile = Module(new HuttRegFile(xlen, hasDebugPorts = hasDebugPorts))
  val alu     = Module(new HuttAlu(xlen))

  // HuttRegFile ties these to 0 when !hasDebugPorts.
  io.dbgS1 := regFile.io.dbgS1
  io.dbgA5 := regFile.io.dbgA5
  io.dbgS3 := regFile.io.dbgS3
  io.dbgS5 := regFile.io.dbgS5
  io.dbgS2 := regFile.io.dbgS2
  io.dbgA0 := regFile.io.dbgA0
  io.dbgRa := regFile.io.dbgRa

  val dbgTrapSeqReg      = dbgReg(32)
  val dbgTrapFromPcReg   = dbgReg()
  val dbgTrapCauseReg    = dbgReg()
  val dbgTrapToPrivReg   = dbgReg(2)
  val dbgTrapTargetPcReg = dbgReg()
  io.dbgTrapSeq      := rdW(dbgTrapSeqReg, 32)
  io.dbgTrapFromPc   := rd(dbgTrapFromPcReg)
  io.dbgTrapCause    := rd(dbgTrapCauseReg)
  io.dbgTrapToPriv   := rdW(dbgTrapToPrivReg, 2)
  io.dbgTrapTargetPc := rd(dbgTrapTargetPcReg)
  io.dbgTrapMtvec := mtvec
  io.dbgTrapStvec := rd(stvecOpt)
  def dbgTrapLatch(fromPc: UInt, cause: UInt, toPriv: UInt, targetPc: UInt): Unit = {
    if (hasDebugPorts) {
      dbgTrapSeqReg.get      := dbgTrapSeqReg.get + 1.U
      dbgTrapFromPcReg.get   := fromPc
      dbgTrapCauseReg.get    := cause
      dbgTrapToPrivReg.get   := toPriv
      dbgTrapTargetPcReg.get := targetPc
    }
  }

  val dbgPtwFillSeqReg   = dbgReg(32)
  val dbgPtwFillVAReg    = dbgReg()
  val dbgPtwFillPPNReg   = dbgReg(44)
  val dbgPtwFillLevelReg = dbgReg(2)
  val dbgStoreSeqReg       = dbgReg(32)
  val dbgStorePcReg        = dbgReg()
  val dbgStorePhysAddrReg  = dbgReg(dataAddrWidth)
  val dbgStoreVAReg2       = dbgReg()
  val dbgStoreDataReg      = dbgReg()
  val dbgSepcWriteSeqReg = dbgReg(32)
  val dbgSepcWritePcReg  = dbgReg()
  val dbgSepcWriteValReg = dbgReg()
  val dbgSfenceSeqReg = dbgReg(32)
  val dbgSfencePcReg  = dbgReg()
  io.dbgSfenceSeq := rdW(dbgSfenceSeqReg, 32)
  io.dbgSfencePc  := rd(dbgSfencePcReg)
  io.dbgSepcWriteSeq := rdW(dbgSepcWriteSeqReg, 32)
  io.dbgSepcWritePc  := rd(dbgSepcWritePcReg)
  io.dbgSepcWriteVal := rd(dbgSepcWriteValReg)
  val dbgLoadSeqReq      = dbgReg(32)
  val dbgLoadPcReg       = dbgReg()
  val dbgLoadVAReg       = dbgReg()
  val dbgLoadPhysAddrReg = dbgReg()
  val dbgLoadRdReg       = dbgReg(5)
  val dbgLoadValReg      = dbgReg()
  io.dbgLoadSeq      := rdW(dbgLoadSeqReq, 32)
  io.dbgLoadPc       := rd(dbgLoadPcReg)
  io.dbgLoadVA       := rd(dbgLoadVAReg)
  io.dbgLoadPhysAddr := rd(dbgLoadPhysAddrReg)
  io.dbgLoadRd       := rdW(dbgLoadRdReg, 5)
  io.dbgLoadVal      := rd(dbgLoadValReg)
  io.dbgPtwFillSeq   := rdW(dbgPtwFillSeqReg, 32)
  io.dbgPtwFillVA    := rd(dbgPtwFillVAReg)
  io.dbgPtwFillPPN   := rdW(dbgPtwFillPPNReg, 44)
  io.dbgPtwFillLevel := rdW(dbgPtwFillLevelReg, 2)
  io.dbgStoreSeq       := rdW(dbgStoreSeqReg, 32)
  io.dbgStorePc        := rd(dbgStorePcReg)
  io.dbgStorePhysAddr  := rdW(dbgStorePhysAddrReg, dataAddrWidth)
  io.dbgStoreVA        := rd(dbgStoreVAReg2)
  io.dbgStoreData      := rd(dbgStoreDataReg)

  // sCsrSel: second CSR read cycle. sDivExec: waits for the multi-cycle divider.
  val sFetchReq :: sFetchResp :: sExec :: sCsrSel :: sDivExec :: sMemReq :: sMemResp :: sPtwReq :: sPtwResp :: sAmoLoadReq :: sAmoLoadResp :: sAmoStoreReq :: sAmoStoreResp :: Nil = Enum(13)
  val state = RegInit(sFetchReq)

  val d = HuttDecode(instr, xlen)

  regFile.io.rs1Addr := d.rs1
  regFile.io.rs2Addr := d.rs2
  val rs1Val = regFile.io.rs1Data
  val rs2Val = regFile.io.rs2Data

  val aluUseImm = d.isOpImm || d.isOpImm32 || d.isLoad || d.isStore || d.isJalr
  val aluImm = MuxCase(d.iImm, Seq(d.isStore -> d.sImm))
  alu.io.a := rs1Val
  alu.io.b := Mux(aluUseImm, aluImm, rs2Val)
  alu.io.op := MuxCase(AluOp.Add, Seq(
    d.isOp      -> HuttAluDecode(d.funct3, d.funct7, isOpReg = true.B),
    d.isOpImm   -> HuttAluDecode(d.funct3, d.funct7, isOpReg = false.B),
    d.isOp32    -> HuttAluDecode(d.funct3, d.funct7, isOpReg = true.B,  isWord = true.B),
    d.isOpImm32 -> HuttAluDecode(d.funct3, d.funct7, isOpReg = false.B, isWord = true.B)
  ))
  alu.io.divStart := false.B
  val isDivOp = Seq(AluOp.Div, AluOp.Divu, AluOp.Rem, AluOp.Remu,
                     AluOp.DivW, AluOp.DivuW, AluOp.RemW, AluOp.RemuW)
    .map(_ === alu.io.op).reduce(_ || _)

  val branchTaken = riscv.Rv.branchTaken(d.funct3, rs1Val, rs2Val)
  val pcPlus4   = pc + 4.U
  val branchTgt = pc + d.bImm
  val jalTgt    = pc + d.jImm
  val jalrTgt   = Cat((rs1Val + d.iImm)(xlen - 1, 1), 0.U(1.W))

  val pcNext = WireDefault(pcPlus4)
  when(d.isBranch && branchTaken) { pcNext := branchTgt }
  when(d.isJal)                   { pcNext := jalTgt }
  when(d.isJalr)                  { pcNext := jalrTgt }

  val csrAddr = instr(31, 20)
  // A flat ~20-way 64-bit select is far too slow on ECP5 (a serial carry chain that
  // firtool flattens back however it is written), so CSR reads are two stages:
  // narrow 6-term OR groups plus a 2-bit group select here, a 4-way pick in sCsrSel.
  val sModeCsrEntries: Seq[(String, UInt)] = if (hasSupervisorMode) Seq(
    "h100" -> (mstatus & SSTATUS_MASK),  // sstatus = mstatus masked
    "h104" -> (mie     & SIE_SIP_MASK),  // sie
    "h105" -> stvecOpt.get,
    "h140" -> sscratchOpt.get,
    "h141" -> sepcOpt.get,
    "h142" -> scauseOpt.get,
    "h143" -> stvalOpt.get,
    "h144" -> (mip     & SIE_SIP_MASK),  // sip
    "h302" -> medelegOpt.get,
    "h303" -> midelegOpt.get
  ) else Seq.empty
  val csrEntries: Seq[(String, UInt)] = sModeCsrEntries ++ Seq(
    "h180" -> satp,
    "h300" -> mstatus,
    "h301" -> misa,
    "h304" -> mie,
    "h305" -> mtvec,
    "h340" -> mscratch,
    "h341" -> mepc,
    "h342" -> mcause,
    "h343" -> mtval,
    "h344" -> mip,
    "hC00" -> cycleCounter,   // cycle
    "hB00" -> cycleCounter,   // mcycle
    // time: readable from S/U mode per the privileged spec; same clock as CLINT's mtime.
    // Linux's clockevent needs it, or mtimecmp is armed already expired.
    "hC01" -> cycleCounter   // time
    // Every other CSR reads 0.
  )
  val csrGroups: Seq[Seq[(String, UInt)]] = csrEntries.grouped(6).toSeq

  // Stage 1 (combinational, during sExec).
  val csrGroupValComb = VecInit(csrGroups.map { terms =>
    terms.map { case (addrHex, value) => Mux(csrAddr === addrHex.U, value, 0.U(xlen.W)) }
         .reduce(_ | _)
  })
  val csrGroupSelComb = MuxCase(0.U(2.W), csrGroups.zipWithIndex.flatMap { case (terms, gi) =>
    terms.map { case (addrHex, _) => (csrAddr === addrHex.U) -> gi.U(2.W) }
  })

  // Stage 2, registered on sExec -> sCsrSel; without pipelinedCsrRead, read stage 1 directly.
  val csrPipeRegs: Option[(Vec[UInt], UInt)] =
    if (pipelinedCsrRead) Some((Reg(Vec(csrGroups.size, UInt(xlen.W))), Reg(UInt(2.W)))) else None
  val csrReadData: UInt = csrPipeRegs match {
    case Some((valReg, selReg)) => valReg(selReg)
    case None                   => csrGroupValComb(csrGroupSelComb)
  }

  // These unimplemented CSRs must trap: OpenSBI probes extensions by whether a read traps.
  //   - stimecmp/stimecmph (Sstc): else OpenSBI arms them instead of CLINT's mtimecmp.
  //   - mtopi/stopi (Smaia/Ssaia): else its trap handler loops on mtopi, which reads 0,
  //     and never services the M-mode timer.
  // Trapping on every unknown CSR breaks OpenSBI's priv-version detection.
  val csrForcesIllegal = Seq("h14D", "h15D", "hFB0", "hDB0")
    .map(_.U(12.W)).map(_ === csrAddr).reduce(_ || _)

  val csrZimm   = Cat(0.U((xlen - 5).W), d.rs1)
  val csrSrc    = Mux(d.funct3(2), csrZimm, rs1Val)
  val csrNewVal = MuxCase(csrSrc, Seq(
    (d.funct3(1, 0) === "b10".U) -> (csrReadData | csrSrc),
    (d.funct3(1, 0) === "b11".U) -> (csrReadData & ~csrSrc)
  ))

  // Write actions for every writable CSR, grouped by the same csrGroups as the read side.
  val csrWriters: Map[String, () => Unit] = (
    (if (hasSupervisorMode) Seq[(String, () => Unit)](
      "h100" -> (() => { mstatus := (mstatus & ~SSTATUS_MASK) | (csrNewVal & SSTATUS_MASK) }),
      "h104" -> (() => {
        mie := (mie & ~SIE_SIP_MASK) | (csrNewVal & SIE_SIP_MASK)
        if (HuttDebug.timerTrace) printf("[HUTT-TIMER] sie write csrNewVal=0x%x -> mie=0x%x\n",
          csrNewVal, (mie & ~SIE_SIP_MASK) | (csrNewVal & SIE_SIP_MASK))
      }),
      "h105" -> (() => { stvecOpt.get    := Cat(csrNewVal(xlen - 1, 2), 0.U(2.W)) }),
      "h140" -> (() => { sscratchOpt.get := csrNewVal }),
      "h141" -> (() => {
        sepcOpt.get := Cat(csrNewVal(xlen - 1, 1), 0.U(1.W))
        if (hasDebugPorts) {
          dbgSepcWriteSeqReg.get := dbgSepcWriteSeqReg.get + 1.U
          dbgSepcWritePcReg.get  := pc
          dbgSepcWriteValReg.get := Cat(csrNewVal(xlen - 1, 1), 0.U(1.W))
        }
      }),
      "h142" -> (() => { scauseOpt.get := csrNewVal }),
      "h143" -> (() => { stvalOpt.get  := csrNewVal }),
      "h144" -> (() => {
        mip_sw := (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR)
        if (HuttDebug.timerTrace) printf("[HUTT-TIMER] sip write csrNewVal=0x%x -> mip_sw=0x%x\n",
          csrNewVal, (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR))
      }),
      "h302" -> (() => { medelegOpt.get := csrNewVal }),
      "h303" -> (() => { midelegOpt.get := csrNewVal })
    ) else Seq.empty[(String, () => Unit)]) ++
    Seq[(String, () => Unit)](
      "h180" -> (() => {
        satp := csrNewVal
        // The TLB has no ASID, and Linux skips sfence.vma on context switches.
        if (xlen == 64) { for (i <- 0 until TLB_ENTRIES) { tlbValid(i) := false.B } }
      }),
      "h300" -> (() => { mstatus  := csrNewVal }),
      "h304" -> (() => {
        mie := csrNewVal
        if (HuttDebug.timerTrace) printf("[HUTT-TIMER] mie write -> 0x%x\n", csrNewVal)
      }),
      "h305" -> (() => { mtvec    := Cat(csrNewVal(xlen - 1, 2), 0.U(2.W)) }),
      "h340" -> (() => { mscratch := csrNewVal }),
      "h341" -> (() => { mepc     := Cat(csrNewVal(xlen - 1, 1), 0.U(1.W)) }),
      "h342" -> (() => { mcause   := csrNewVal }),
      "h343" -> (() => { mtval    := csrNewVal }),
      "h344" -> (() => {
        mip_sw := (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR)
        if (HuttDebug.timerTrace) printf("[HUTT-TIMER] mip write csrNewVal=0x%x -> mip_sw=0x%x\n",
          csrNewVal, (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR))
      })
    )
  ).toMap

  // when() chains: chisel3's switch only accepts literal is() children, not generated ones.
  def csrWriteDecode(groupSel: UInt): Unit = {
    csrGroups.zipWithIndex.foreach { case (group, idx) =>
      when(groupSel === idx.U) {
        group.foreach { case (addrHex, _) =>
          csrWriters.get(addrHex).foreach { writer =>
            when(csrAddr === addrHex.U) { writer() }
          }
        }
      }
    }
  }

  // M-mode trap entry: MPP←privLevel, MPIE←MIE, MIE←0; S-mode bits unchanged.
  val mstatusTrap = Cat(
    mstatus(xlen - 1, 13),
    privLevel,             // MPP ← current privilege level
    mstatus(10, 8),
    mstatus(3),            // MPIE ← MIE
    mstatus(6, 4),
    false.B,               // MIE ← 0
    mstatus(2, 0)
  )
  // M-mode return: MPP←0(U), MPIE←1, MIE←MPIE.
  val mstatusMret = Cat(
    mstatus(xlen - 1, 13),
    "b00".U(2.W),          // MPP ← U-mode
    mstatus(10, 8),
    true.B,                // MPIE ← 1
    mstatus(6, 4),
    mstatus(7),            // MIE ← MPIE
    mstatus(2, 0)
  )
  // S-mode trap entry: SPP←privLevel[0], SPIE←SIE, SIE←0.
  val mstatusStrap = Cat(
    mstatus(xlen - 1, 9),
    privLevel(0),          // SPP ← privLevel bit 0 (0=U, 1=S)
    mstatus(7, 6),
    mstatus(1),            // SPIE ← SIE
    mstatus(4, 2),
    false.B,               // SIE ← 0
    mstatus(0)
  )
  // S-mode return: SPP←0(U), SPIE←1, SIE←SPIE.
  val mstatusSret = Cat(
    mstatus(xlen - 1, 9),
    false.B,               // SPP ← 0 (U-mode)
    mstatus(7, 6),
    true.B,                // SPIE ← 1
    mstatus(4, 2),
    mstatus(5),            // SIE ← SPIE
    mstatus(0)
  )
  // Trap target addresses (direct mode only: bits[1:0] forced to 0).
  val mTrapPC = Cat(mtvec(xlen - 1, 2), 0.U(2.W))
  val sTrapPC = Cat(rd(stvecOpt)(xlen - 1, 2), 0.U(2.W))

  // ECALL cause = f(privilege level): U→8, S→9, M→11.
  val ecallCause = MuxLookup(privLevel, 11.U(xlen.W))(Seq(
    0.U -> 8.U(xlen.W),
    1.U -> 9.U(xlen.W),
    3.U -> 11.U(xlen.W)
  ))

  // M-mode trap entry; also the only target when !hasSupervisorMode.
  def trapToM(cause: UInt, tval: UInt): Unit = {
    mepc := pc; mcause := cause; mtval := tval
    mstatus := mstatusTrap; pc := mTrapPC; privLevel := 3.U
    dbgTrapLatch(pc, cause, 3.U, mTrapPC)
  }
  // S-mode trap entry; call only under `if (hasSupervisorMode)`.
  def trapToS(cause: UInt, tval: UInt): Unit = {
    sepcOpt.get := pc; scauseOpt.get := cause; stvalOpt.get := tval
    if (hasDebugPorts) {
      dbgSepcWriteSeqReg.get := dbgSepcWriteSeqReg.get + 1.U
      dbgSepcWritePcReg.get  := pc
      dbgSepcWriteValReg.get := pc
    }
    mstatus := mstatusStrap; pc := sTrapPC; privLevel := 1.U
    dbgTrapLatch(pc, cause, 1.U, sTrapPC)
  }

  val wbAlu    = alu.io.out
  val wbLink   = pcPlus4
  val wbUpper  = d.uImm
  val wbAuipc  = pc + d.uImm
  val wbExec   = MuxCase(wbAlu, Seq(
    d.isJal   -> wbLink,
    d.isJalr  -> wbLink,
    d.isLui   -> wbUpper,
    d.isAuipc -> wbAuipc,
    d.isCsr   -> csrReadData
  ))
  val wbExecEn = (d.isOp || d.isOpImm || d.isOp32 || d.isOpImm32 ||
                  d.isJal || d.isJalr || d.isLui || d.isAuipc || d.isCsr) && (d.rd =/= 0.U)

  val memAddr    = (rs1Val.asSInt + Mux(d.isStore, d.sImm.asSInt, d.iImm.asSInt)).asUInt
  val loadAddrLo = Reg(UInt(2.W))
  val loadFunct3 = Reg(UInt(3.W))
  val loadRd     = Reg(UInt(5.W))
  val isLoadOp   = Reg(Bool())

  def extendLoad(funct3: UInt, raw: UInt): UInt = riscv.Rv.loadExtend(funct3, raw, xlen)

  io.instr.req.valid  := false.B
  io.instr.req.bits   := pc(instrAddrWidth + 1, 2)
  io.instr.resp.ready := false.B

  io.data.req.valid      := false.B
  io.data.req.bits.addr  := memAddr(dataAddrWidth - 1, 0)
  io.data.req.bits.write := d.isStore
  io.data.req.bits.size  := d.funct3(1, 0)
  io.data.req.bits.data  := rs2Val
  io.data.resp.ready     := false.B

  regFile.io.wen   := false.B
  regFile.io.wAddr := d.rd
  regFile.io.wData := wbExec

  switch(state) {

    is(sFetchReq) {
      // M-mode timer interrupt: fires when (in M-mode → MIE=1, else always) AND mip/mie.MTIP set.
      val mTimerPend = mie(7) && mip(7) && Mux(privLevel === 3.U, mstatus(3), true.B)
      // S-mode timer interrupt: fires in S/U mode when (in S → SIE=1, else always) AND mip/mie.STIP set.
      val sTimerPend = mie(5) && mip(5) && (privLevel =/= 3.U) &&
        Mux(privLevel === 1.U, mstatus(1), true.B)

      // Built as a Scala-level chain so the S-mode branch can be omitted when !hasSupervisorMode.
      val timerAfterM = when(mTimerPend) {
        if (HuttDebug.timerTrace) printf("[HUTT-TIMER] M-mode timer trap taken pc=0x%x priv=%d mie=0x%x mip=0x%x\n",
          pc, privLevel, mie, mip)
        mepc      := pc
        mcause    := Cat(true.B, 0.U((xlen - 4).W), 7.U(3.W))
        mtval     := 0.U(xlen.W)
        mstatus   := mstatusTrap
        pc        := mTrapPC
        privLevel := 3.U
        lrValid   := false.B
        dbgTrapLatch(pc, Cat(true.B, 0.U((xlen - 4).W), 7.U(3.W)), 3.U, mTrapPC)
      }
      val timerAfterS = if (hasSupervisorMode) {
        timerAfterM.elsewhen(sTimerPend) {
          if (HuttDebug.timerTrace) printf("[HUTT-TIMER] S-mode timer trap taken pc=0x%x priv=%d mie=0x%x mip=0x%x\n",
            pc, privLevel, mie, mip)
          sepcOpt.get := pc
          if (hasDebugPorts) {
            dbgSepcWriteSeqReg.get := dbgSepcWriteSeqReg.get + 1.U
            dbgSepcWritePcReg.get  := pc
            dbgSepcWriteValReg.get := pc
          }
          scauseOpt.get := Cat(true.B, 0.U((xlen - 4).W), 5.U(3.W))
          stvalOpt.get  := 0.U(xlen.W)
          mstatus   := mstatusStrap
          dbgTrapLatch(pc, Cat(true.B, 0.U((xlen - 4).W), 5.U(3.W)), 1.U, sTrapPC)
          pc        := sTrapPC
          privLevel := 1.U
          lrValid   := false.B
        }
      } else timerAfterM
      timerAfterS.otherwise {
        if (xlen == 64) {
          when(mmuEnabled) {
            val tlbIdx = pc(TLB_BITS + 11, 12)
            // A hit also needs the cached PTE's X bit, or the fetch must fault.
            when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === pc(38, 12)) && tlbFlags(tlbIdx)(3)) {
              // TLB hit: issue fetch using physical address.
              val physFetch = Cat(tlbPPN(tlbIdx), pc(11, 0))
              io.instr.req.valid := true.B
              io.instr.req.bits  := physFetch(instrAddrWidth + 1, 2)
              when(io.instr.req.fire) { state := sFetchResp }
            }.otherwise {
              // TLB miss: start 3-level Sv39 page-table walk for instruction.
              ptwVA      := pc
              ptwIsInstr := true.B
              ptwIsWrite := false.B
              ptwLevel   := 2.U
              ptwA       := Cat(0.U((xlen - 56).W), satp(43, 0), 0.U(12.W))
              state      := sPtwReq
            }
          }.otherwise {
            io.instr.req.valid := true.B
            when(io.instr.req.fire) { state := sFetchResp }
          }
        } else {
          io.instr.req.valid := true.B
          when(io.instr.req.fire) { state := sFetchResp }
        }
      }
    }

    is(sFetchResp) {
      io.instr.resp.ready := true.B
      when(io.instr.resp.fire) {
        instr := io.instr.resp.bits
        state := sExec
      }
    }

    is(sExec) {
      if (HuttDebug.trace) printf("[HUTT] pc=0x%x instr=0x%x priv=%d mstatus=0x%x mepc=0x%x mcause=0x%x mtvec=0x%x\n",
        pc, instr, privLevel, mstatus, mepc, mcause, mtvec)
      if (HuttDebug.trace) printf("[HUTT-REG] rs1=%d rs2=%d rd=%d rs1Val=0x%x rs2Val=0x%x wbExec=0x%x wbExecEn=%d wen=%d wAddr=%d wData=0x%x\n",
        d.rs1, d.rs2, d.rd, rs1Val, rs2Val, wbExec, wbExecEn, regFile.io.wen, regFile.io.wAddr, regFile.io.wData)
      when(d.isLoad || d.isStore) {
        loadAddrLo := memAddr(1, 0)
        loadFunct3 := d.funct3
        loadRd     := d.rd
        isLoadOp   := d.isLoad
        amoPending := 0.U
        if (xlen == 64) {
          when(mmuEnabled) {
            val tlbIdx = memAddr(TLB_BITS + 11, 12)
            // A hit also needs the PTE to allow the access (R for loads, W&&R for stores),
            // as the PTW's permOk does; else a store bypasses copy-on-write.
            val tlbPermOk = Mux(d.isStore, tlbFlags(tlbIdx)(2) && tlbFlags(tlbIdx)(1), tlbFlags(tlbIdx)(1))
            when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === memAddr(38, 12)) && tlbPermOk) {
              // TLB hit: form physical address and proceed directly to sMemReq.
              dataPhysAddr := Cat(tlbPPN(tlbIdx), memAddr(11, 0))
              dataVAReg    := memAddr
              state        := sMemReq
            }.otherwise {
              // TLB miss: 3-level Sv39 page-table walk for data.
              ptwVA      := memAddr
              ptwIsInstr := false.B
              ptwIsWrite := d.isStore
              ptwLevel   := 2.U
              ptwA       := Cat(0.U((xlen - 56).W), satp(43, 0), 0.U(12.W))
              state      := sPtwReq
            }
          }.otherwise {
            dataPhysAddr := memAddr
            dataVAReg    := memAddr
            state        := sMemReq
          }
        } else {
          state := sMemReq
        }

      }.elsewhen(d.isAmo) {
        if (xlen == 64) {
          // A-extension addressing has no immediate offset: address = rs1.
          val amoAddr = rs1Val
          when(d.isSc) {
            // SC only touches the bus if the reservation is live and matches;
            // a mismatch fails immediately with no side effects.
            when(lrValid && (lrAddr === amoAddr)) {
              amoPending := 2.U
              amoNewVal  := rs2Val
              when(mmuEnabled) {
                val tlbIdx = amoAddr(TLB_BITS + 11, 12)
                // SC is a write: W&&R, as for a store.
                when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === amoAddr(38, 12)) &&
                     tlbFlags(tlbIdx)(2) && tlbFlags(tlbIdx)(1)) {
                  dataPhysAddr := Cat(tlbPPN(tlbIdx), amoAddr(11, 0))
                  state        := sAmoStoreReq
                }.otherwise {
                  ptwVA      := amoAddr
                  ptwIsInstr := false.B
                  ptwIsWrite := true.B
                  ptwLevel   := 2.U
                  ptwA       := Cat(0.U((xlen - 56).W), satp(43, 0), 0.U(12.W))
                  state      := sPtwReq
                }
              }.otherwise {
                dataPhysAddr := amoAddr
                state        := sAmoStoreReq
              }
            }.otherwise {
              regFile.io.wAddr := d.rd
              regFile.io.wData := 1.U(xlen.W)   // failure
              regFile.io.wen   := (d.rd =/= 0.U)
              lrValid := false.B
              pc    := pcNext
              state := sFetchReq
            }
          }.otherwise {
            // LR and true AMO* both start with a read phase.
            amoPending := 1.U
            when(mmuEnabled) {
              val tlbIdx = amoAddr(TLB_BITS + 11, 12)
              // R only. The AMO write-back reuses dataPhysAddr without a W recheck.
              when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === amoAddr(38, 12)) && tlbFlags(tlbIdx)(1)) {
                dataPhysAddr := Cat(tlbPPN(tlbIdx), amoAddr(11, 0))
                state        := sAmoLoadReq
              }.otherwise {
                ptwVA      := amoAddr
                ptwIsInstr := false.B
                ptwIsWrite := false.B
                ptwLevel   := 2.U
                ptwA       := Cat(0.U((xlen - 56).W), satp(43, 0), 0.U(12.W))
                state      := sPtwReq
              }
            }.otherwise {
              dataPhysAddr := amoAddr
              state        := sAmoLoadReq
            }
          }
        } else {
          // A extension is only decoded for xlen=64 builds.
          pc    := pcNext
          state := sFetchReq
        }

      }.elsewhen(d.isEcall) {
        if (HuttDebug.timerTrace) {
          // SBI calling convention: a7=x17=extension ID, a6=x16=function ID.
          // ecall decodes rs1=rs2=0, so borrow the read ports to observe them.
          regFile.io.rs1Addr := 17.U
          regFile.io.rs2Addr := 16.U
          when(privLevel === 1.U) {
            printf("[HUTT-TIMER] S-mode ecall a7(ext)=0x%x a6(fid)=0x%x pc=0x%x\n",
              regFile.io.rs1Data, regFile.io.rs2Data, pc)
          }
        }
        // Delegation: S-mode ECALL (cause 9) → medeleg[9]; U-mode → medeleg[8].
        if (hasSupervisorMode) {
          val ecallDeleg = Mux(privLevel === 1.U, medelegOpt.get(9),
                            Mux(privLevel === 0.U, medelegOpt.get(8), false.B))
          when(ecallDeleg) { trapToS(ecallCause, 0.U(xlen.W)) }
            .otherwise      { trapToM(ecallCause, 0.U(xlen.W)) }
        } else {
          trapToM(ecallCause, 0.U(xlen.W))
        }
        lrValid := false.B
        state := sFetchReq

      }.elsewhen(d.isEbreak) {
        if (hasSupervisorMode) {
          when((privLevel =/= 3.U) && medelegOpt.get(3)) { trapToS(3.U(xlen.W), pc) }
            .otherwise                                    { trapToM(3.U(xlen.W), pc) }
        } else {
          trapToM(3.U(xlen.W), pc)
        }
        lrValid := false.B
        state := sFetchReq

      }.elsewhen(d.isMret) {
        mstatus   := mstatusMret
        pc        := mepc
        privLevel := mstatus(12, 11)   // read MPP before mstatusMret clears it
        state     := sFetchReq

      }.elsewhen(d.isSret) {
        // Without S-mode, treat a stray SRET as a no-op.
        if (hasSupervisorMode) {
          mstatus   := mstatusSret
          pc        := sepcOpt.get
          privLevel := Cat(0.U(1.W), mstatus(8))  // SPP → S(1) or U(0)
        } else {
          pc := pcNext
        }
        state := sFetchReq

      }.elsewhen(d.isSfenceVma) {
        if (xlen == 64) { for (i <- 0 until TLB_ENTRIES) { tlbValid(i) := false.B } }
        if (hasDebugPorts) { dbgSfenceSeqReg.get := dbgSfenceSeqReg.get + 1.U; dbgSfencePcReg.get := pc }
        pc    := pcNext
        state := sFetchReq

      }.otherwise {
        val csrIllegal = d.isCsr && csrForcesIllegal
        when(csrIllegal) {
          if (HuttDebug.timerTrace) printf("[HUTT-TIMER] illegal CSR (stimecmp) trap pc=0x%x priv=%d\n", pc, privLevel)
          // Illegal instruction (cause 2), delegated per medeleg[2].
          if (hasSupervisorMode) {
            when((privLevel =/= 3.U) && medelegOpt.get(2)) { trapToS(2.U(xlen.W), instr) }
              .otherwise                                    { trapToM(2.U(xlen.W), instr) }
          } else {
            trapToM(2.U(xlen.W), instr)
          }
          lrValid := false.B
          state := sFetchReq
        }.elsewhen(d.isCsr) {
          if (pipelinedCsrRead) {
            // Latch the stage 1 groups; sCsrSel finishes the write and commit.
            val (valReg, selReg) = csrPipeRegs.get
            valReg := csrGroupValComb
            selReg := csrGroupSelComb
            state := sCsrSel
          } else {
            // Single-cycle CSR read and write.
            csrWriteDecode(csrGroupSelComb)
            regFile.io.wen := wbExecEn
            pc    := pcNext
            state := sFetchReq
          }
        }.elsewhen(isDivOp) {
          // alu.io.a/b/op stay stable from the held `instr` while the divider runs.
          alu.io.divStart := true.B
          state := sDivExec
        }.otherwise {
          regFile.io.wen := wbExecEn
          pc    := pcNext
          state := sFetchReq
        }
      }
    }

    is(sDivExec) {
      when(alu.io.divDone) {
        regFile.io.wen := wbExecEn
        pc    := pcNext
        state := sFetchReq
      }
    }

    is(sCsrSel) {
      // Guard inside is(): switch only accepts literal is() children.
      if (pipelinedCsrRead) {
        // Gating on the registered group select keeps the write decode small.
        csrWriteDecode(csrPipeRegs.get._2)
        regFile.io.wen := wbExecEn
        pc    := pcNext
        state := sFetchReq
      }
    }

    is(sMemReq) {
      io.data.req.valid := true.B
      if (xlen == 64) { io.data.req.bits.addr := dataPhysAddr(dataAddrWidth - 1, 0) }
      if (HuttDebug.trace) printf("[HUTT-MEM] sMemReq addr=0x%x write=%d size=%d data=0x%x req.ready=%d req.fire=%d\n",
        io.data.req.bits.addr, io.data.req.bits.write, io.data.req.bits.size, io.data.req.bits.data,
        io.data.req.ready, io.data.req.fire)
      when(io.data.req.fire) {
        when(io.data.req.bits.write) {
          if (hasDebugPorts) {
            dbgStoreSeqReg.get      := dbgStoreSeqReg.get + 1.U
            dbgStorePcReg.get       := pc
            dbgStorePhysAddrReg.get := io.data.req.bits.addr
            dbgStoreVAReg2.get      := dataVAReg
            dbgStoreDataReg.get     := io.data.req.bits.data
          }
        }
        state := sMemResp
      }
    }

    is(sMemResp) {
      io.data.resp.ready := true.B
      if (HuttDebug.trace) printf("[HUTT-MEM] sMemResp resp.valid=%d resp.fire=%d resp.bits=0x%x\n",
        io.data.resp.valid, io.data.resp.fire, io.data.resp.bits)
      when(io.data.resp.fire) {
        when(isLoadOp && (loadRd =/= 0.U)) {
          val loadExtended = extendLoad(loadFunct3, io.data.resp.bits)
          regFile.io.wAddr := loadRd
          regFile.io.wData := loadExtended
          regFile.io.wen   := true.B
          if (hasDebugPorts) {
            dbgLoadSeqReq.get      := dbgLoadSeqReq.get + 1.U
            dbgLoadPcReg.get       := pc
            dbgLoadVAReg.get       := dataVAReg
            dbgLoadPhysAddrReg.get := dataPhysAddr
            dbgLoadRdReg.get       := loadRd
            dbgLoadValReg.get      := loadExtended
          }
        }.elsewhen(!isLoadOp) {
          // A plain store to the reserved address invalidates an LR reservation.
          when(lrValid && (lrAddr === memAddr)) { lrValid := false.B }
        }
        pc    := pcNext
        state := sFetchReq
      }
    }

    // Sv39 page-table walker (xlen=64 only).
    is(sPtwReq) {
      if (xlen == 64) {
        val vpn2   = ptwVA(38, 30)
        val vpn1   = ptwVA(29, 21)
        val vpn0   = ptwVA(20, 12)
        val curVPN = MuxLookup(ptwLevel, vpn0)(Seq(2.U -> vpn2, 1.U -> vpn1))
        // PTE byte address = page-table base + VPN-for-this-level * 8
        io.data.req.valid      := true.B
        io.data.req.bits.addr  := (ptwA + (curVPN << 3))(dataAddrWidth - 1, 0)
        io.data.req.bits.write := false.B
        io.data.req.bits.size  := 3.U   // 64-bit doubleword
        io.data.req.bits.data  := 0.U
        when(io.data.req.fire) { state := sPtwResp }
      }
    }

    is(sPtwResp) {
      if (xlen == 64) {
        io.data.resp.ready := true.B
        when(io.data.resp.fire) {
          val pte  = io.data.resp.bits
          val pteV = pte(0); val pteR = pte(1); val pteW = pte(2); val pteX = pte(3)

          val isLeaf = pteV && (pteR || pteX)

          // Permission check for leaf PTEs.
          val permOk = Mux(ptwIsInstr, pteX,
                       Mux(ptwIsWrite, pteW && pteR, pteR))

          // TLB PPN (44 bits) respecting superpage levels.
          val pte_ppn  = pte(53, 10)
          val tlbPpnSel = MuxLookup(ptwLevel, pte_ppn)(Seq(
            2.U -> Cat(pte(53, 28), ptwVA(29, 12)),  // 1 GB: top-26 PPN + VA[29:12]
            1.U -> Cat(pte(53, 19), ptwVA(20, 12))   // 2 MB: top-35 PPN + VA[20:12]
          ))

          // Physical byte address of the fault/access.
          val physAddr = Cat(tlbPpnSel, ptwVA(11, 0))

          // Page-fault cause and delegation bit.
          val faultCause = Mux(ptwIsInstr, 12.U(xlen.W),
                           Mux(ptwIsWrite, 15.U(xlen.W), 13.U(xlen.W)))
          // Every xlen=64 config has hasSupervisorMode; otherwise .get fails at elaboration.
          val faultDeleg = Mux(ptwIsInstr, medelegOpt.get(12),
                           Mux(ptwIsWrite, medelegOpt.get(15), medelegOpt.get(13)))

          def doFault(): Unit = {
            when(faultDeleg && (privLevel =/= 3.U)) {
              trapToS(faultCause, ptwVA)
            }.otherwise {
              trapToM(faultCause, ptwVA)
            }
            state := sFetchReq
          }

          // TLB index (direct-mapped by VA[TLB_BITS+11:12]).
          val tlbIdx = ptwVA(TLB_BITS + 11, 12)

          when(!pteV || (pteW && !pteR)) {
            // Invalid PTE or reserved encoding.
            doFault()
          }.elsewhen(isLeaf) {
            when(!permOk) {
              doFault()
            }.otherwise {
              // Install TLB entry and continue execution.
              tlbValid(tlbIdx) := true.B
              tlbVPN(tlbIdx)   := ptwVA(38, 12)
              tlbPPN(tlbIdx)   := tlbPpnSel
              tlbFlags(tlbIdx) := pte(7, 0)
              if (hasDebugPorts) {
                dbgPtwFillSeqReg.get   := dbgPtwFillSeqReg.get + 1.U
                dbgPtwFillVAReg.get    := ptwVA
                dbgPtwFillPPNReg.get   := tlbPpnSel
                dbgPtwFillLevelReg.get := ptwLevel
              }
              when(ptwIsInstr) {
                // Re-enter sFetchReq — TLB will hit this time.
                state := sFetchReq
              }.otherwise {
                dataPhysAddr := physAddr
                dataVAReg    := ptwVA
                // Redirect per the operation that triggered this walk: plain
                // load/store (sMemReq), or the read/write phase of an AMO/LR/SC.
                state := MuxLookup(amoPending, sMemReq)(Seq(
                  1.U -> sAmoLoadReq,
                  2.U -> sAmoStoreReq
                ))
              }
            }
          }.otherwise {
            // Non-leaf PTE: descend to next level or fault at level 0.
            when(ptwLevel === 0.U) {
              doFault()   // all 3 levels exhausted without finding a leaf
            }.otherwise {
              ptwLevel := ptwLevel - 1.U
              ptwA     := Cat(0.U((xlen - 56).W), pte(53, 10), 0.U(12.W))
              state    := sPtwReq
            }
          }
        }
      }
    }

    // A extension (xlen=64 only): LR uses the read phase, SC the write phase, AMO* both.
    is(sAmoLoadReq) {
      if (xlen == 64) {
        io.data.req.valid      := true.B
        io.data.req.bits.addr  := dataPhysAddr(dataAddrWidth - 1, 0)
        io.data.req.bits.write := false.B
        io.data.req.bits.size  := d.funct3(1, 0)
        io.data.req.bits.data  := 0.U
        when(io.data.req.fire) { state := sAmoLoadResp }
      }
    }

    is(sAmoLoadResp) {
      if (xlen == 64) {
        io.data.resp.ready := true.B
        when(io.data.resp.fire) {
          val loadedVal = extendLoad(d.funct3, io.data.resp.bits)
          amoOldVal := loadedVal
          when(d.isLr) {
            regFile.io.wAddr := d.rd
            regFile.io.wData := loadedVal
            regFile.io.wen   := (d.rd =/= 0.U)
            lrValid := true.B
            lrAddr  := rs1Val
            pc    := pcNext
            state := sFetchReq
          }.otherwise {
            val amoOp2 = rs2Val
            amoNewVal := riscv.Rv.amoResult(d.amoFunct5, loadedVal, amoOp2, other = loadedVal)
            state := sAmoStoreReq
          }
        }
      }
    }

    is(sAmoStoreReq) {
      if (xlen == 64) {
        io.data.req.valid      := true.B
        io.data.req.bits.addr  := dataPhysAddr(dataAddrWidth - 1, 0)
        io.data.req.bits.write := true.B
        io.data.req.bits.size  := d.funct3(1, 0)
        io.data.req.bits.data  := amoNewVal
        when(io.data.req.fire) { state := sAmoStoreResp }
      }
    }

    is(sAmoStoreResp) {
      if (xlen == 64) {
        io.data.resp.ready := true.B
        when(io.data.resp.fire) {
          when(d.isSc) {
            regFile.io.wAddr := d.rd
            regFile.io.wData := 0.U(xlen.W)   // success
            regFile.io.wen   := (d.rd =/= 0.U)
            lrValid := false.B
          }.otherwise {
            regFile.io.wAddr := d.rd
            regFile.io.wData := amoOldVal
            regFile.io.wen   := (d.rd =/= 0.U)
            // A true-AMO write invalidates a matching outstanding reservation.
            when(lrValid && (lrAddr === rs1Val)) { lrValid := false.B }
          }
          pc    := pcNext
          state := sFetchReq
        }
      }
    }
  }

  // Placed after the switch to see the final regFile write of the cycle.
  val dbgX1WriteSeqReg = dbgReg(32)
  val dbgX1WritePcReg  = dbgReg()
  val dbgX1WriteValReg = dbgReg()
  if (hasDebugPorts) {
    when(regFile.io.wen && regFile.io.wAddr === 1.U) {
      dbgX1WriteSeqReg.get := dbgX1WriteSeqReg.get + 1.U
      dbgX1WritePcReg.get  := pc
      dbgX1WriteValReg.get := regFile.io.wData
    }
  }
  io.dbgX1WriteSeq := rdW(dbgX1WriteSeqReg, 32)
  io.dbgX1WritePc  := rd(dbgX1WritePcReg)
  io.dbgX1WriteVal := rd(dbgX1WriteValReg)

  val dbgX18WriteSeqReg = dbgReg(32)
  val dbgX18WritePcReg  = dbgReg()
  val dbgX18WriteValReg = dbgReg()
  if (hasDebugPorts) {
    when(regFile.io.wen && regFile.io.wAddr === 18.U) {
      dbgX18WriteSeqReg.get := dbgX18WriteSeqReg.get + 1.U
      dbgX18WritePcReg.get  := pc
      dbgX18WriteValReg.get := regFile.io.wData
    }
  }
  io.dbgX18WriteSeq := rdW(dbgX18WriteSeqReg, 32)
  io.dbgX18WritePc  := rd(dbgX18WritePcReg)
  io.dbgX18WriteVal := rd(dbgX18WriteValReg)

  io.dbgRegWen   := regFile.io.wen
  io.dbgRegWAddr := regFile.io.wAddr
  io.dbgRegWData := regFile.io.wData

  io.dbgState     := state.asUInt
  io.dbgWbExecEn  := wbExecEn
  io.dbgDRd       := d.rd
  io.dbgInstr     := instr
  io.dbgSatp        := satp
  io.dbgPrivLevel   := privLevel
}
