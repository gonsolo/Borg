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
  * MMU (xlen=64 only): Sv39 — 3-level page table, 16-entry direct-mapped TLB.
  *   satp.MODE=8 enables translation in S/U mode.  SFENCE.VMA flushes TLB.
  *   Note: TLB hits skip permission re-check (safe for RWX mappings; X-only
  *   pages may mis-allow data access — acceptable for Phase 3 / OpenSBI).
  *   mstatus.MPRV not yet implemented.
  *
  * No compressed instructions.
  *
  * @param resetVector word-aligned byte address of the first instruction
  */
class Hutt(
    val instrAddrWidth: Int = 23,
    val dataAddrWidth: Int  = 28,
    val resetVector: BigInt = 0,
    val xlen: Int = 32
) extends Module {

  val io = IO(new HuttIO(instrAddrWidth, dataAddrWidth, xlen))

  // -- Architectural state ---------------------------------------------------
  val pc    = RegInit(resetVector.U(xlen.W))
  val instr = RegInit(0.U(32.W))

  val cycleCounter = RegInit(0.U(xlen.W))
  cycleCounter := cycleCounter + 1.U

  // -- Privilege level: 3=M, 1=S, 0=U.  Reset to M-mode. -------------------
  val privLevel = RegInit(3.U(2.W))

  // -- M-mode CSRs -----------------------------------------------------------
  // mstatus: bit 1=SIE, 3=MIE, 5=SPIE, 7=MPIE, 8=SPP, 12:11=MPP.
  val mstatus  = RegInit(0.U(xlen.W))
  val mtvec    = RegInit(0.U(xlen.W))
  val mscratch = RegInit(0.U(xlen.W))
  val mepc     = RegInit(0.U(xlen.W))
  val mcause   = RegInit(0.U(xlen.W))
  val mtval    = RegInit(0.U(xlen.W))
  val mie      = RegInit(0.U(xlen.W))
  val medeleg  = RegInit(0.U(xlen.W))
  val mideleg  = RegInit(0.U(xlen.W))
  // mip: bit 7 MTIP is hardware (CLINT); bits 5/1 STIP/SSIP are software-set.
  val mip_sw  = RegInit(0.U(xlen.W))
  val mip     = Cat(0.U((xlen - 8).W), io.interrupt, 0.U(7.W)) | mip_sw
  // misa: MXL field + I + S extensions.
  val misaMxl = if (xlen == 64) 2.U(2.W) else 1.U(2.W)
  val misa    = Cat(misaMxl, 0.U((xlen - 28).W),
                    ((1 << ('I' - 'A')) | (1 << ('S' - 'A'))).U(26.W))

  // -- S-mode CSRs -----------------------------------------------------------
  val stvec    = RegInit(0.U(xlen.W))
  val sscratch = RegInit(0.U(xlen.W))
  val sepc     = RegInit(0.U(xlen.W))
  val scause   = RegInit(0.U(xlen.W))
  val stval    = RegInit(0.U(xlen.W))
  // satp: Sv39 address-translation CSR (0x180).  Meaningful only for xlen=64.
  //   MODE[63:60]=8 → Sv39; ASID[59:44]; PPN[43:0] of root page table.
  val satp     = RegInit(0.U(xlen.W))

  // -- Sv39 TLB (16-entry direct-mapped; xlen=64 only) ----------------------
  val TLB_ENTRIES = 16
  val TLB_BITS    = 4   // log2(TLB_ENTRIES)
  val tlbValid = RegInit(VecInit(Seq.fill(TLB_ENTRIES)(false.B)))
  val tlbVPN   = Reg(Vec(TLB_ENTRIES, UInt(27.W)))  // VA[38:12]
  val tlbPPN   = Reg(Vec(TLB_ENTRIES, UInt(44.W)))  // PA page (4KB granularity)
  val tlbFlags = Reg(Vec(TLB_ENTRIES, UInt(8.W)))   // PTE[7:0]

  // -- Page-table walker state (xlen=64 only) --------------------------------
  val ptwVA      = Reg(UInt(xlen.W))   // virtual address being translated
  val ptwLevel   = Reg(UInt(2.W))      // PTW level (2=root, 0=leaf)
  val ptwA       = Reg(UInt(xlen.W))   // current page-table base (byte address)
  val ptwIsInstr = RegInit(false.B)    // true = instruction fetch, false = data
  val ptwIsWrite = RegInit(false.B)    // true = store (for fault cause)
  val dataPhysAddr = Reg(UInt(xlen.W)) // physical address for data access

  // -- A extension: LR/SC/AMO* (xlen=64 only) --------------------------------
  // amoPending tells the PTW's data-fault-resolved redirect which state to
  // continue into once translation completes: 0=plain load/store (sMemReq),
  // 1=AMO/LR read phase (sAmoLoadReq), 2=AMO/SC write phase (sAmoStoreReq).
  val amoPending = RegInit(0.U(2.W))
  val amoOldVal  = Reg(UInt(xlen.W))   // value loaded in the read phase (old value for rd)
  val amoNewVal  = Reg(UInt(xlen.W))   // value to write in the store phase
  // Single-hart reservation: valid+address set by LR, checked+cleared by SC.
  // No other bus master can interleave within one hart's own instruction
  // execution, so this is sufficient for correctness on this SoC.
  val lrValid = RegInit(false.B)
  val lrAddr  = Reg(UInt(xlen.W))

  // mmuEnabled: Sv39 translation active (xlen=64, non-M-mode, MODE=8).
  val mmuEnabled: Bool =
    if (xlen == 64) (satp(63, 60) === 8.U && privLevel =/= 3.U) else false.B

  // -- CSR masks -------------------------------------------------------------
  // sstatus: S-mode visible bits of mstatus (MXR=19, SUM=18, SPP=8, SPIE=5, SIE=1).
  val SSTATUS_MASK = 0xC0122L.U(xlen.W)
  // sie/sip: S-mode view of mie/mip (SEIE=9, STIE=5, SSIE=1).
  val SIE_SIP_MASK = 0x222L.U(xlen.W)
  // mip software-writable bits: STIP(5), SSIP(1).
  val MIP_SW_WR    = 0x22L.U(xlen.W)

  val regFile = Module(new HuttRegFile(xlen))
  val alu     = Module(new HuttAlu(xlen))

  // -- FSM -------------------------------------------------------------------
  val sFetchReq :: sFetchResp :: sExec :: sMemReq :: sMemResp :: sPtwReq :: sPtwResp :: sAmoLoadReq :: sAmoLoadResp :: sAmoStoreReq :: sAmoStoreResp :: Nil = Enum(11)
  val state = RegInit(sFetchReq)

  // -- Decode (combinational over current `instr` register) ------------------
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

  val branchTaken = HuttBranchTaken(d.funct3, rs1Val, rs2Val)
  val pcPlus4   = pc + 4.U
  val branchTgt = pc + d.bImm
  val jalTgt    = pc + d.jImm
  val jalrTgt   = Cat((rs1Val + d.iImm)(xlen - 1, 1), 0.U(1.W))

  val pcNext = WireDefault(pcPlus4)
  when(d.isBranch && branchTaken) { pcNext := branchTgt }
  when(d.isJal)                   { pcNext := jalTgt }
  when(d.isJalr)                  { pcNext := jalrTgt }

  // -- CSR read --------------------------------------------------------------
  val csrAddr = instr(31, 20)
  val csrReadData = MuxLookup(csrAddr, 0.U(xlen.W))(Seq(
    // S-mode CSRs
    "h100".U -> (mstatus & SSTATUS_MASK),  // sstatus = mstatus masked
    "h104".U -> (mie     & SIE_SIP_MASK),  // sie
    "h105".U -> stvec,
    "h140".U -> sscratch,
    "h141".U -> sepc,
    "h142".U -> scause,
    "h143".U -> stval,
    "h144".U -> (mip     & SIE_SIP_MASK),  // sip
    "h180".U -> satp,
    // M-mode CSRs
    "h300".U -> mstatus,
    "h301".U -> misa,
    "h302".U -> medeleg,
    "h303".U -> mideleg,
    "h304".U -> mie,
    "h305".U -> mtvec,
    "h340".U -> mscratch,
    "h341".U -> mepc,
    "h342".U -> mcause,
    "h343".U -> mtval,
    "h344".U -> mip,
    "hF11".U -> 0.U(xlen.W),   // mvendorid
    "hF12".U -> 0.U(xlen.W),   // marchid
    "hF13".U -> 0.U(xlen.W),   // mimpid
    "hF14".U -> 0.U(xlen.W),   // mhartid
    "hC00".U -> cycleCounter,   // cycle
    "hB00".U -> cycleCounter,   // mcycle
    "hC80".U -> 0.U(xlen.W),   // cycleh (hi half)
    "hB80".U -> 0.U(xlen.W),   // mcycleh
    // time (unprivileged read-only view of the mtimer, mandated by the
    // privileged spec to be readable from S/U mode without an M-mode trap).
    // cycleCounter free-runs every cycle from reset in the same clock domain
    // as CLINT's mtime, so it's numerically identical — no new wiring needed.
    // Without this, `rdtime`/get_cycles64() silently read 0 (unimplemented
    // CSRs default to 0 here), so Linux's timer clockevent computed
    // next_event = 0 + delta instead of real_mtime + delta: an
    // already-expired mtimecmp that fired the M-mode timer trap on every
    // single cycle forever, starving S-mode of any forward progress.
    "hC01".U -> cycleCounter   // time
  ))

  // A handful of specific unimplemented CSRs must raise an illegal-instruction
  // exception rather than silently no-op like other unimplemented CSRs, because
  // OpenSBI's hart-feature probing (sbi_hart.c's __check_ext_csr) treats "did
  // reading this CSR trap?" as "is the extension present?" — a non-trapping
  // phantom register makes OpenSBI wrongly believe real hardware exists:
  //   - stimecmp/stimecmph (Sstc, 0x14D/0x15D): sbi_timer_event_start() writes
  //     this believed-real CSR instead of arming CLINT's mtimecmp MMIO
  //     register, so the timer interrupt never fires.
  //   - mtopi/stopi (Smaia/Ssaia, 0xFB0/0xDB0): sbi_trap_handler() dispatches
  //     M-mode interrupts via `while (csr_read(CSR_MTOPI)) {...}` instead of
  //     checking mip/mie directly; since mtopi always reads 0, the loop body
  //     (which calls sbi_timer_process()/sbi_ipi_process(), the code that
  //     actually clears MIE.MTIP or asserts mip.STIP) never runs at all — the
  //     M-mode timer trap then re-fires every single cycle forever, since
  //     mtimecmp is level-triggered and nothing ever rearms or masks it.
  // Both combined previously hung Linux forever waiting for jiffies to
  // advance. Other unimplemented CSRs (mcounteren, menvcfg, mstateen0, ...)
  // stay silently accepted: OpenSBI/Linux tolerate those false-detected
  // extensions fine, and blanket-trapping every unrecognized address (tried
  // first) broke priv-version detection itself.
  val csrForcesIllegal = Seq("h14D", "h15D", "hFB0", "hDB0")
    .map(_.U(12.W)).map(_ === csrAddr).reduce(_ || _)

  // -- CSR write value -------------------------------------------------------
  val csrZimm   = Cat(0.U((xlen - 5).W), d.rs1)
  val csrSrc    = Mux(d.funct3(2), csrZimm, rs1Val)
  val csrNewVal = MuxCase(csrSrc, Seq(
    (d.funct3(1, 0) === "b10".U) -> (csrReadData | csrSrc),
    (d.funct3(1, 0) === "b11".U) -> (csrReadData & ~csrSrc)
  ))

  // -- mstatus update helpers ------------------------------------------------
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
  val sTrapPC = Cat(stvec(xlen - 1, 2), 0.U(2.W))

  // ECALL cause = f(privilege level): U→8, S→9, M→11.
  val ecallCause = MuxLookup(privLevel, 11.U(xlen.W))(Seq(
    0.U -> 8.U(xlen.W),
    1.U -> 9.U(xlen.W),
    3.U -> 11.U(xlen.W)
  ))

  // -- Writeback logic -------------------------------------------------------
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

  def extendLoad(funct3: UInt, raw: UInt): UInt = {
    def sext(from: Int): UInt =
      if (xlen <= from) raw(xlen - 1, 0) else Cat(Fill(xlen - from, raw(from - 1)), raw(from - 1, 0))
    def zext(from: Int): UInt =
      if (xlen <= from) raw(xlen - 1, 0) else Cat(0.U((xlen - from).W), raw(from - 1, 0))
    MuxLookup(funct3, raw)(Seq(
      "b000".U -> sext(8),
      "b001".U -> sext(16),
      "b010".U -> sext(32),
      "b011".U -> raw,
      "b100".U -> zext(8),
      "b101".U -> zext(16),
      "b110".U -> zext(32)
    ))
  }

  // -- IO defaults -----------------------------------------------------------
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

  // -- FSM transitions -------------------------------------------------------
  switch(state) {

    is(sFetchReq) {
      // M-mode timer interrupt: fires when (in M-mode → MIE=1, else always) AND mip/mie.MTIP set.
      val mTimerPend = mie(7) && mip(7) && Mux(privLevel === 3.U, mstatus(3), true.B)
      // S-mode timer interrupt: fires in S/U mode when (in S → SIE=1, else always) AND mip/mie.STIP set.
      val sTimerPend = mie(5) && mip(5) && (privLevel =/= 3.U) &&
        Mux(privLevel === 1.U, mstatus(1), true.B)

      when(mTimerPend) {
        if (HuttDebug.timerTrace) printf("[HUTT-TIMER] M-mode timer trap taken pc=0x%x priv=%d mie=0x%x mip=0x%x\n",
          pc, privLevel, mie, mip)
        mepc      := pc
        mcause    := Cat(true.B, 0.U((xlen - 4).W), 7.U(3.W))
        mtval     := 0.U(xlen.W)
        mstatus   := mstatusTrap
        pc        := mTrapPC
        privLevel := 3.U
        lrValid   := false.B
      }.elsewhen(sTimerPend) {
        if (HuttDebug.timerTrace) printf("[HUTT-TIMER] S-mode timer trap taken pc=0x%x priv=%d mie=0x%x mip=0x%x\n",
          pc, privLevel, mie, mip)
        sepc      := pc
        scause    := Cat(true.B, 0.U((xlen - 4).W), 5.U(3.W))
        stval     := 0.U(xlen.W)
        mstatus   := mstatusStrap
        pc        := sTrapPC
        privLevel := 1.U
        lrValid   := false.B
      }.otherwise {
        if (xlen == 64) {
          when(mmuEnabled) {
            val tlbIdx = pc(TLB_BITS + 11, 12)
            when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === pc(38, 12))) {
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
            when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === memAddr(38, 12))) {
              // TLB hit: form physical address and proceed directly to sMemReq.
              dataPhysAddr := Cat(tlbPPN(tlbIdx), memAddr(11, 0))
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
                when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === amoAddr(38, 12))) {
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
              when(tlbValid(tlbIdx) && (tlbVPN(tlbIdx) === amoAddr(38, 12))) {
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
          // ecall itself decodes rs1=rs2=0, so temporarily steal the regfile's
          // read ports (unused elsewhere in this branch) to observe them.
          regFile.io.rs1Addr := 17.U
          regFile.io.rs2Addr := 16.U
          when(privLevel === 1.U) {
            printf("[HUTT-TIMER] S-mode ecall a7(ext)=0x%x a6(fid)=0x%x pc=0x%x\n",
              regFile.io.rs1Data, regFile.io.rs2Data, pc)
          }
        }
        // Delegation: S-mode ECALL (cause 9) → medeleg[9]; U-mode → medeleg[8].
        val ecallDeleg = Mux(privLevel === 1.U, medeleg(9),
                          Mux(privLevel === 0.U, medeleg(8), false.B))
        when(ecallDeleg) {
          sepc := pc; scause := ecallCause; stval := 0.U(xlen.W)
          mstatus := mstatusStrap; pc := sTrapPC; privLevel := 1.U
        }.otherwise {
          mepc := pc; mcause := ecallCause; mtval := 0.U(xlen.W)
          mstatus := mstatusTrap; pc := mTrapPC; privLevel := 3.U
        }
        lrValid := false.B
        state := sFetchReq

      }.elsewhen(d.isEbreak) {
        when((privLevel =/= 3.U) && medeleg(3)) {
          sepc := pc; scause := 3.U(xlen.W); stval := pc
          mstatus := mstatusStrap; pc := sTrapPC; privLevel := 1.U
        }.otherwise {
          mepc := pc; mcause := 3.U(xlen.W); mtval := pc
          mstatus := mstatusTrap; pc := mTrapPC; privLevel := 3.U
        }
        lrValid := false.B
        state := sFetchReq

      }.elsewhen(d.isMret) {
        mstatus   := mstatusMret
        pc        := mepc
        privLevel := mstatus(12, 11)   // read MPP before mstatusMret clears it
        state     := sFetchReq

      }.elsewhen(d.isSret) {
        mstatus   := mstatusSret
        pc        := sepc
        privLevel := Cat(0.U(1.W), mstatus(8))  // SPP → S(1) or U(0)
        state     := sFetchReq

      }.elsewhen(d.isSfenceVma) {
        if (xlen == 64) { for (i <- 0 until TLB_ENTRIES) { tlbValid(i) := false.B } }
        pc    := pcNext
        state := sFetchReq

      }.otherwise {
        val csrIllegal = d.isCsr && csrForcesIllegal
        when(csrIllegal) {
          if (HuttDebug.timerTrace) printf("[HUTT-TIMER] illegal CSR (stimecmp) trap pc=0x%x priv=%d medeleg=0x%x\n",
            pc, privLevel, medeleg)
          // Illegal instruction (cause 2): delegate to S-mode per medeleg[2],
          // same pattern as isEcall/isEbreak above.
          when((privLevel =/= 3.U) && medeleg(2)) {
            sepc := pc; scause := 2.U(xlen.W); stval := instr
            mstatus := mstatusStrap; pc := sTrapPC; privLevel := 1.U
          }.otherwise {
            mepc := pc; mcause := 2.U(xlen.W); mtval := instr
            mstatus := mstatusTrap; pc := mTrapPC; privLevel := 3.U
          }
          lrValid := false.B
        }.elsewhen(d.isCsr) {
          switch(csrAddr) {
            // S-mode CSR writes
            is("h100".U) { mstatus  := (mstatus & ~SSTATUS_MASK) | (csrNewVal & SSTATUS_MASK) }
            is("h104".U) {
              mie := (mie & ~SIE_SIP_MASK) | (csrNewVal & SIE_SIP_MASK)
              if (HuttDebug.timerTrace) printf("[HUTT-TIMER] sie write csrNewVal=0x%x -> mie=0x%x\n",
                csrNewVal, (mie & ~SIE_SIP_MASK) | (csrNewVal & SIE_SIP_MASK))
            }
            is("h105".U) { stvec    := Cat(csrNewVal(xlen - 1, 2), 0.U(2.W)) }
            is("h140".U) { sscratch := csrNewVal }
            is("h141".U) { sepc     := Cat(csrNewVal(xlen - 1, 1), 0.U(1.W)) }
            is("h142".U) { scause   := csrNewVal }
            is("h143".U) { stval    := csrNewVal }
            is("h144".U) {
              mip_sw := (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR)
              if (HuttDebug.timerTrace) printf("[HUTT-TIMER] sip write csrNewVal=0x%x -> mip_sw=0x%x\n",
                csrNewVal, (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR))
            }
            is("h180".U) { satp     := csrNewVal }
            // M-mode CSR writes
            is("h300".U) { mstatus  := csrNewVal }
            is("h302".U) { medeleg  := csrNewVal }
            is("h303".U) { mideleg  := csrNewVal }
            is("h304".U) {
              mie := csrNewVal
              if (HuttDebug.timerTrace) printf("[HUTT-TIMER] mie write -> 0x%x\n", csrNewVal)
            }
            is("h305".U) { mtvec    := Cat(csrNewVal(xlen - 1, 2), 0.U(2.W)) }
            is("h340".U) { mscratch := csrNewVal }
            is("h341".U) { mepc     := Cat(csrNewVal(xlen - 1, 1), 0.U(1.W)) }
            is("h342".U) { mcause   := csrNewVal }
            is("h343".U) { mtval    := csrNewVal }
            is("h344".U) {
              mip_sw := (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR)
              if (HuttDebug.timerTrace) printf("[HUTT-TIMER] mip write csrNewVal=0x%x -> mip_sw=0x%x\n",
                csrNewVal, (mip_sw & ~MIP_SW_WR) | (csrNewVal & MIP_SW_WR))
            }
          }
        }
        when(!csrIllegal) {
          regFile.io.wen := wbExecEn
          pc    := pcNext
        }
        state := sFetchReq
      }
    }

    is(sMemReq) {
      io.data.req.valid := true.B
      if (xlen == 64) { io.data.req.bits.addr := dataPhysAddr(dataAddrWidth - 1, 0) }
      if (HuttDebug.trace) printf("[HUTT-MEM] sMemReq addr=0x%x write=%d size=%d data=0x%x req.ready=%d req.fire=%d\n",
        io.data.req.bits.addr, io.data.req.bits.write, io.data.req.bits.size, io.data.req.bits.data,
        io.data.req.ready, io.data.req.fire)
      when(io.data.req.fire) { state := sMemResp }
    }

    is(sMemResp) {
      io.data.resp.ready := true.B
      if (HuttDebug.trace) printf("[HUTT-MEM] sMemResp resp.valid=%d resp.fire=%d resp.bits=0x%x\n",
        io.data.resp.valid, io.data.resp.fire, io.data.resp.bits)
      when(io.data.resp.fire) {
        when(isLoadOp && (loadRd =/= 0.U)) {
          regFile.io.wAddr := loadRd
          regFile.io.wData := extendLoad(loadFunct3, io.data.resp.bits)
          regFile.io.wen   := true.B
        }.elsewhen(!isLoadOp) {
          // A plain store to the reserved address invalidates an LR reservation.
          when(lrValid && (lrAddr === memAddr)) { lrValid := false.B }
        }
        pc    := pcNext
        state := sFetchReq
      }
    }

    // -- Sv39 page-table walker (xlen=64 only) --------------------------------
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
          val faultDeleg = Mux(ptwIsInstr, medeleg(12),
                           Mux(ptwIsWrite, medeleg(15), medeleg(13)))

          def doFault(): Unit = {
            when(faultDeleg && (privLevel =/= 3.U)) {
              sepc := pc; scause := faultCause; stval := ptwVA
              mstatus := mstatusStrap; pc := sTrapPC; privLevel := 1.U
            }.otherwise {
              mepc := pc; mcause := faultCause; mtval := ptwVA
              mstatus := mstatusTrap; pc := mTrapPC; privLevel := 3.U
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
              when(ptwIsInstr) {
                // Re-enter sFetchReq — TLB will hit this time.
                state := sFetchReq
              }.otherwise {
                dataPhysAddr := physAddr
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

    // -- A extension: LR/SC/AMO* read-modify-write (xlen=64 only) -------------
    // LR uses only the read phase (sAmoLoadReq/sAmoLoadResp); SC uses only the
    // write phase (sAmoStoreReq/sAmoStoreResp, gated on a live reservation in
    // sExec before ever reaching here); true AMO* ops use both phases in
    // sequence. Single-hart, non-pipelined execution makes the read-modify-
    // write inherently atomic from this hart's own perspective.
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
            amoNewVal := MuxLookup(d.amoFunct5, loadedVal)(Seq(
              AmoFunct5.Swap -> amoOp2,
              AmoFunct5.Add  -> (loadedVal + amoOp2),
              AmoFunct5.Xor  -> (loadedVal ^ amoOp2),
              AmoFunct5.And  -> (loadedVal & amoOp2),
              AmoFunct5.Or   -> (loadedVal | amoOp2),
              AmoFunct5.Min  -> Mux(loadedVal.asSInt < amoOp2.asSInt, loadedVal, amoOp2),
              AmoFunct5.Max  -> Mux(loadedVal.asSInt > amoOp2.asSInt, loadedVal, amoOp2),
              AmoFunct5.Minu -> Mux(loadedVal < amoOp2, loadedVal, amoOp2),
              AmoFunct5.Maxu -> Mux(loadedVal > amoOp2, loadedVal, amoOp2)
            ))
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
}
