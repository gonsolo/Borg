// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import chisel3.{ExtModule, StringParam}
import chisel3.experimental.{Analog, attach}
import borg.BorgConfig
import memory.{Ecp5PllParams, Ecp5PllWrapper, FlashBootLoader, SdramBackend, Usrmclk}
import _root_.circt.stage.ChiselStage

/** ECP5 bidirectional buffer primitive (Lattice cell name: BB).
  *
  * Used for the SDRAM DQ bus — each bit can be driven (write phase) or
  * sampled (read phase) depending on T:
  *   T = 0 → drive I onto pad B
  *   T = 1 → high-Z; pad voltage readable on O
  */
class Ecp5BiDirBuf extends ExtModule {
  override def desiredName = "BB"  // must match the Lattice/nextpnr cell name
  val B = IO(Analog(1.W))
  val T = IO(Input(Bool()))
  val I = IO(Input(Bool()))
  val O = IO(Output(Bool()))
}

/** ULX3S (Lattice ECP5-85K) top-level — SDRAM + flash boot.
  *
  * Clock: 25 MHz oscillator → Ecp5Pll → 125 MHz system clock.
  * Memory: onboard SDRAM (IS42S16160G) via SdramBackend.
  * Boot: FlashBootLoader copies firmware from flash 0x400000 → SDRAM 0x0.
  *       Hutt starts only after boot_done && pll_locked.
  * UART: ftdi_rxd = FPGA→host TX (debug output at 115200 baud).
  */
class ulx3s_top(val CLOCK_MHZ: Int, val borgModeOverride: BorgMode = BorgDirect) extends RawModule with SoCLogic {
  // samples=4: 4x MSAA (Step 50.2), on top of 2×2 quad SIMT fragment shading.
  // Verified on real ULX3S hardware: vkcube renders correctly at 39 % LUT,
  // 15 % FF, 13.5 % BRAM on the ECP5-85K, timing closed at 25 MHz.
  // Revert to plain BorgConfig.Simt to fall back to the HPG-proven config.
  override def BORG_CFG: BorgConfig = BorgConfig.Simt.copy(samples = 4)
  // RV32I Hutt (Project.scala's default): the RV64IMAC + Sv39 MMU config
  // this used to override to no longer fits the ECP5-85K alongside FP32
  // Borg (92,986 LUT4 at RV64, 111% of the device, even after fixing
  // BorgTileSequencer's dirty-bit blowup -- see BorgConfig.scala's
  // maxBinTiles=4096 comment and BorgTileSequencer's tileDirtyMem doc).
  // RV32I alone measures 3,537 LUT4 against RV64's 13,401 -- the single
  // biggest lever available without cutting a Borg feature. Costs the
  // M extension (no hardware MUL/DIV: firmware falls back to libgcc's
  // software routines) and the ULX3S OpenSBI/Linux goal on THIS
  // bitstream -- that work continues on MinimalSocSimTop/
  // MinimalSocRealSdramSimTop and their own borg-minimal-linux.bit,
  // which stay RV64 and are untouched by this.
  //
  // hasSupervisorMode = false too, matching QspiSocTop: this is the same
  // software/borg bare-metal firmware that comment describes -- never
  // leaves M-mode, never sets mtvec, no ecall/mret/sret anywhere -- so
  // every S-mode CSR and trap-delegation path Hutt elaborates is dead
  // weight here too, not just on the ASIC/TT target.
  //
  // Measured (nextpnr, whole design): RV64 92,986 LUT4 -> RV32 84,580 ->
  // RV32 without S-mode 84,232 of 83,640 (100.7%). Still does NOT place;
  // the remaining ~4K needs the legacy draw path gone (see
  // docs/B1_geometry_front_end.md's "Coexistence" section).
  override def hasSupervisorMode: Boolean = false
  override def scanoutCurBuf: Bool = scanout.io.curBuf
  // Rung A of the wafer.space Borg-only bridge's on-hardware ladder (see the
  // plan doc / BorgMode's own comment): BorgLoopback closes peripherals.io.link
  // on a same-bitstream BorgLinkSlave + Borg pair via wireBorgLoopback() below,
  // so every Borg access -- CPU-initiated MMIO and Borg-initiated gpuMem --
  // travels the real link RTL with zero ASIC involvement. Default BorgDirect
  // (today's behaviour, what the demo/talk bitstream ships) is unaffected;
  // only ULX3SLoopbackMain below overrides this.
  override def borgMode: BorgMode = borgModeOverride
  // Rung B needs two pads per logical wire (both endpoints are on this chip),
  // which only fits J1+J2 at the narrow width -- see BorgMode's doc.
  override def linkParams: borg.link.LinkParams =
    if (borgModeOverride == BorgPadLoop) borg.link.LinkParams(w = 8)
    else borg.link.LinkParams()

  // ── Board clock and reset ──────────────────────────────────────────────────
  val clk_25mhz = IO(Input(Clock()))
  val rst_n      = IO(Input(Bool()))   // BTN_PWRn, active-low

  // ── SDRAM pins (IS42S16160G-7TL, 16-bit) ──────────────────────────────────
  val sdram_clk  = IO(Output(Clock()))
  val sdram_cke  = IO(Output(Bool()))
  val sdram_csn  = IO(Output(Bool()))
  val sdram_wen  = IO(Output(Bool()))
  val sdram_rasn = IO(Output(Bool()))
  val sdram_casn = IO(Output(Bool()))
  val sdram_a    = IO(Output(UInt(13.W)))
  val sdram_ba   = IO(Output(UInt(2.W)))
  val sdram_dqm  = IO(Output(UInt(2.W)))
  val sdram_d    = IO(Vec(16, Analog(1.W)))   // bidirectional DQ bus

  // ── Onboard flash pins (Winbond W25Q128JV) ────────────────────────────────
  // flash_clk is routed via the USRMCLK primitive — no IO port needed.
  val flash_csn  = IO(Output(Bool()))
  val flash_mosi = IO(Output(Bool()))
  val flash_miso = IO(Input(Bool()))

  // ── UART ──────────────────────────────────────────────────────────────────
  val ftdi_rxd = IO(Output(Bool()))   // FPGA → host TX
  val ftdi_txd = IO(Input(Bool()))    // host → FPGA RX

  // ── HDMI (GPDI) ───────────────────────────────────────────────────────────
  val gpdi_dp = IO(Output(UInt(4.W)))

  // ── LEDs and buttons ──────────────────────────────────────────────────────
  val led = IO(Output(UInt(8.W)))
  val btn = IO(Input(UInt(6.W)))

  // ── Wafer.space Borg-only bridge link pins (BorgExternal, rungs B/C) ──────
  // Mirrors BorgLinkMasterIO's own directions exactly (see BorgLinkPortsIO's
  // doc) -- only declared when this specific top is built for BorgExternal
  // (ULX3SExternalMain), so every other target (the demo bitstream, rung A's
  // loopback) has zero extra pins from this block. dbg_sel/link_narrow are
  // wired to real pins but not yet consumed by any RTL -- reserved lane-map
  // positions, same status as BorgOnlyTop's own ASIC-side pins (see its doc).
  val dn_d        = if (borgMode == BorgExternal) Some(IO(Output(UInt(16.W)))) else None
  val dn_v        = if (borgMode == BorgExternal) Some(IO(Output(Bool())))     else None
  val dn_p        = if (borgMode == BorgExternal) Some(IO(Output(Bool())))     else None
  val dn_cred     = if (borgMode == BorgExternal) Some(IO(Input(Bool())))      else None
  val up_d        = if (borgMode == BorgExternal) Some(IO(Input(UInt(16.W)))) else None
  val up_v        = if (borgMode == BorgExternal) Some(IO(Input(Bool())))      else None
  val up_p        = if (borgMode == BorgExternal) Some(IO(Input(Bool())))      else None
  val up_cred     = if (borgMode == BorgExternal) Some(IO(Output(Bool())))     else None
  // far_link_up reads the far side's link_up pin -- a real ASIC or second board
  // in rung C, which is what BorgExternal is for.
  //
  // There is deliberately no `link_up_loop` companion. It used to exist so a
  // single-board ribbon could jumper this master's own link_up back into
  // far_link_up, but that cannot work: the master's linkUp IS its farLinkUp
  // (see BorgLinkClockGen), so the cable closes farLinkUp = linkUp = farLinkUp,
  // a combinational loop a pulled-down pad holds at 0 forever -- the link could
  // never train. Single-board loopback is BorgPadLoop, which routes the SLAVE's
  // linkUp (a constant true) out and back instead.
  val far_link_up  = if (borgMode == BorgExternal) Some(IO(Input(Bool())))  else None
  // Control straps -- onboard DIP switches (SW1-4), not GP/GN pins.
  val dbg_sel     = if (borgMode == BorgExternal) Some(IO(Input(UInt(2.W)))) else None
  val link_narrow = if (borgMode == BorgExternal) Some(IO(Input(Bool())))    else None
  val link_fast   =
    if (borgMode == BorgExternal || borgMode == BorgPadLoop) Some(IO(Input(Bool()))) else None

  // ── Rung B pad-loop pins (BorgPadLoop, ULX3SPadLoopMain) ─────────────────
  // Master AND slave are both on this FPGA, so unlike BorgExternal above every
  // logical wire needs a driving pad and a receiving pad, with the ribbon
  // bridging the two halves of one J1/J2 pin. 23 pairs at w=8; see BorgMode's
  // doc for why w=16 (78 holes) does not fit and rung B cannot reuse
  // BorgExternal.
  private def padLoop = borgMode == BorgPadLoop
  // master -> pads -> cable -> pads -> slave
  val pl_dn_d_out   = if (padLoop) Some(IO(Output(UInt(8.W)))) else None
  val pl_dn_v_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_dn_p_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_dn_d_in    = if (padLoop) Some(IO(Input(UInt(8.W))))  else None
  val pl_dn_v_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_dn_p_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_upcred_out = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_upcred_in  = if (padLoop) Some(IO(Input(Bool())))     else None
  // slave -> pads -> cable -> pads -> master
  val pl_up_d_out   = if (padLoop) Some(IO(Output(UInt(8.W)))) else None
  val pl_up_v_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_up_p_out   = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_up_d_in    = if (padLoop) Some(IO(Input(UInt(8.W))))  else None
  val pl_up_v_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_up_p_in    = if (padLoop) Some(IO(Input(Bool())))     else None
  val pl_dncred_out = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_dncred_in  = if (padLoop) Some(IO(Input(Bool())))     else None
  // Carries the SLAVE's linkUp (constant true) out and back into the master's
  // farLinkUp -- NOT the master's own linkUp, which would deadlock.
  val pl_linkup_out = if (padLoop) Some(IO(Output(Bool())))    else None
  val pl_linkup_in  = if (padLoop) Some(IO(Input(Bool())))     else None

  // ── PLL: 25 MHz osc → SoC + SoC/90° SDRAM + 125 MHz HDMI ──────────
  // SoC clock = the build's CLOCK_MHZ.  SINGLE SOURCE OF TRUTH is ULX3S_MHZ in
  // fpga/ulx3s/Makefile — it drives this PLL clock, the debug-UART baud divider
  // (SoCLogic), AND the firmware's CLOCK_MHZ together.  NB the HDMI pixel clock
  // == SoC clock, so this also sets the video pixel rate.  Kept BELOW the Borg
  // FPU's ~16 MHz Fmax until the FPU timing is fixed: the FPU is used by CPU
  // geometry via MMIO (borg_fpu.c) — NOT bypassed — and returns 0 above Fmax.
  val SOC_MHZ = CLOCK_MHZ
  val HDMI_MHZ = 125
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = SOC_MHZ.toLong * 1_000_000L,
    out1Hz = SOC_MHZ.toLong * 1_000_000L, out1Deg = 90,
    out2Hz = HDMI_MHZ.toLong * 1_000_000L
  )))
  pll.io.clk_i   := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)   // 25 MHz — CPU, SDRAM, Borg, scanout
  val sdramClock = pll.io.clk_o(1)   // 25 MHz + 90° — SDRAM clock pin
  val hdmiClock  = pll.io.clk_o(2)   // 125 MHz — TMDS serializer only

  // Route 90°-shifted clock directly to the SDRAM clock pin
  sdram_clk := sdramClock

  // FlashBootLoader and SdramBackend start as soon as the PLL locks.
  // They must NOT be gated on boot_done — boot_done depends on SdramBackend
  // completing a write, which requires SdramBackend to be out of reset.
  val pllRst = !pllLocked

  // ── Warm-reset controller (serial firmware reload) ─────────────────────────
  // Firmware streams a new image into SDRAM scratch, then writes WARM_RESET_MAGIC
  // to PERI_WARM_RESET → wireSoC() raises `warmReset`.  We latch a persistent
  // `warmBootReg` (cleared only by a cold/PLL reset) and pulse `warmRst` for a
  // few cycles.  `warmRst` resets ONLY the bootloader + (via boot_done) the
  // CPU/icache/MemoryController — NOT the PLL, SDRAM backend, scanout, or VGA
  // timing.  So the bootloader re-runs in warm-copy mode (scratch→0) and the CPU
  // reboots with a freshly-flushed icache, while the HDMI sync generator keeps
  // running and the monitor never loses signal.
  // Declared as a Wire because flashBoot's reset needs it, but its value derives
  // from warmReset which wireSoC() produces further below.
  val warmRst     = Wire(Bool())
  val warmBootReg = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

  // ── FlashBootLoader: copies firmware flash→SDRAM before Hutt starts ────────
  // Reset on pllRst (cold) OR warmRst (warm reload). warmBoot selects scratch→0.
  val flashBoot = withClockAndReset(sysClock, pllRst || warmRst) {
    Module(new FlashBootLoader())
  }
  flashBoot.io.warmBoot := warmBootReg

  // Wire USRMCLK: route flashBoot SPI clock to the flash MCLK pin
  val usrmclk = Module(new Usrmclk)
  usrmclk.USRMCLKI  := flashBoot.io.spi_clk.asClock
  usrmclk.USRMCLKTS := false.B   // always enabled (active-low tristate)

  flash_csn  := flashBoot.io.flash_csn
  flash_mosi := flashBoot.io.flash_mosi
  flashBoot.io.flash_miso := flash_miso

  // ── SoCLogic abstract members ──────────────────────────────────────────────
  def soc_clk = sysClock
  def soc_rst_n = pllLocked && flashBoot.io.boot_done && rst_n

  lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
    RegNext(soc_rst_n)
  }

  // ui_in[7]=ftdi_txd (UART RX from host); ui_in[6:1]=btn[5:0]; ui_in[0]=0
  // ftdi_txd at bit 7 feeds PeriUart's default uart_rxd (ui_in(7)), enabling
  // UART receive.  Debug UART TX still routes to ftdi_rxd via gpio_out_sel(0)=0.
  def soc_ui_in = Cat(ftdi_txd, btn, 0.U(1.W))

  // ── HDMI Scanout — declared here so wireGpuMem() can reference it ─────────
  // The framebuffer bases are NOT hardcoded here: firmware programs them via the
  // PERI_SCANOUT_FB0/FB1 registers (scanoutFbBase0/1), from the SAME borg_layout.h
  // constants that drive the GPU flush base — so the scanout and the GPU cannot
  // drift apart (a 0x80 drift here previously caused the blinking green corner
  // pixel).  Wired below once the SoC registers exist.
  val scanout = withClockAndReset(sysClock, pllRst) {
    Module(new HdmiScanoutFp16(fbWidth = 128, fbHeight = 128))
  }

  // ── GPU memory arbiter: Borg GPU writes/reads have priority over scanout ──
  // scanoutOwns is registered so that once the scanout's request is accepted
  // by the MemoryController, the ready pulse is always routed back to the
  // scanout — even if gpuActive goes high while the transaction is in-flight.
  override def wireGpuMem(): Unit = {
    val gpuActive  = peripherals.io.gpuMem.req || peripherals.io.gpuMem.wr
    val scanoutOwns = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

    when(scanoutOwns) {
      when(mem.io.gpuMem.ready) { scanoutOwns := false.B }
    }.otherwise {
      when(!gpuActive && scanout.io.gpuReq) { scanoutOwns := true.B }
    }

    val serveGpu = !scanoutOwns

    mem.io.gpuMem.req   := Mux(serveGpu, peripherals.io.gpuMem.req,   scanout.io.gpuReq)
    mem.io.gpuMem.addr  := Mux(serveGpu, peripherals.io.gpuMem.addr,  scanout.io.gpuAddr)
    mem.io.gpuMem.wr    := Mux(serveGpu, peripherals.io.gpuMem.wr,    false.B)
    mem.io.gpuMem.wdata := peripherals.io.gpuMem.wdata
    // Burst length: only the GPU writes (and bursts); the scanout only reads.
    mem.io.gpuMem.wlen  := Mux(serveGpu, peripherals.io.gpuMem.wlen, 1.U)

    peripherals.io.gpuMem.data    := mem.io.gpuMem.data
    peripherals.io.gpuMem.ready   := mem.io.gpuMem.ready && !scanoutOwns
    // Forward the burst per-word pull to the GPU only while it owns the bus.
    peripherals.io.gpuMem.waccept := mem.io.gpuMem.waccept && serveGpu

    scanout.io.gpuData  := mem.io.gpuMem.data
    scanout.io.gpuReady := mem.io.gpuMem.ready && scanoutOwns
  }

  // ── Wire the SoC ──────────────────────────────────────────────────────────
  val uo_out_val = wireSoC()

  // Rung A: peripherals.io.link is Some(...) whenever borgMode != BorgDirect
  // (see PeripheralsIO) and is left dangling unless something closes it --
  // wireBorgLoopback() is that something for the loopback case.
  if (borgMode == BorgLoopback) wireBorgLoopback()

  // Rungs B/C: close peripherals.io.link on real board pins instead of
  // internal wires. See the dn_d/up_d/... IO block above for the pin set.
  if (borgMode == BorgExternal) {
    val linkIo = peripherals.io.link.getOrElse(
      throw new IllegalStateException("BorgExternal requires peripherals.io.link to be present")
    )
    dn_d.get    := linkIo.dnPins.d
    dn_v.get    := linkIo.dnPins.v
    dn_p.get    := linkIo.dnPins.p
    linkIo.dnCred := dn_cred.get

    linkIo.upPins.d := up_d.get
    linkIo.upPins.v := up_v.get
    linkIo.upPins.p := up_p.get
    up_cred.get := linkIo.upCred

    linkIo.linkFast   := link_fast.get
    linkIo.linkNarrow := link_narrow.get
    linkIo.farLinkUp  := far_link_up.get
    // dbg_sel still only reserves its lane-map position -- no RTL reads it.
    // link_narrow is wired through, but this build is fixed-width (the runtime
    // mux is a narrowCapable build, which is the ASIC's), so the strap is
    // accepted and ignored here rather than silently doing nothing on silicon.
  }

  // ── Rung B: the whole bridge, with the master<->slave path out on pads ────
  if (borgMode == BorgPadLoop) {
    val pads = Wire(new BorgPadLoopIO(linkParams))

    // Drive the outbound pads; sample the inbound ones. The ribbon shorts each
    // *_out to its matching *_in, so these halves meet outside the chip.
    pl_dn_d_out.get := pads.dnOut.d
    pl_dn_v_out.get := pads.dnOut.v
    pl_dn_p_out.get := pads.dnOut.p
    pads.dnIn.d     := pl_dn_d_in.get
    pads.dnIn.v     := pl_dn_v_in.get
    pads.dnIn.p     := pl_dn_p_in.get

    pl_upcred_out.get := pads.upCredOut
    pads.upCredIn     := pl_upcred_in.get

    pl_up_d_out.get := pads.upOut.d
    pl_up_v_out.get := pads.upOut.v
    pl_up_p_out.get := pads.upOut.p
    pads.upIn.d     := pl_up_d_in.get
    pads.upIn.v     := pl_up_v_in.get
    pads.upIn.p     := pl_up_p_in.get

    pl_dncred_out.get := pads.dnCredOut
    pads.dnCredIn     := pl_dncred_in.get

    pl_linkup_out.get := pads.linkUpOut
    pads.linkUpIn     := pl_linkup_in.get

    wireBorgPadLoop(pads)

    // Real strap this time (SW4), unlike rung A's hardcoded safe default.
    peripherals.io.link.get.linkFast := link_fast.get
    // Rung B is already physically 8 lanes wide (see BorgMode), so there is no
    // 16->8 mux to engage; wireBorgPadLoop drives the slave's own side.
    peripherals.io.link.get.linkNarrow := false.B
  }

  // ── Warm-reset controller logic (uses `warmReset` produced by wireSoC) ─────
  // On a warm-reset request: latch warmBootReg (so the re-run bootloader picks
  // the scratch→0 copy) and hold warmRst for a handful of cycles to fully reset
  // the bootloader + CPU.  warmBootReg persists until the next cold/PLL reset.
  val warmRstCtr = withClockAndReset(sysClock, pllRst) { RegInit(0.U(5.W)) }
  when(warmReset) {
    warmBootReg := true.B
    warmRstCtr  := 16.U
  } .elsewhen(warmRstCtr =/= 0.U) {
    warmRstCtr := warmRstCtr - 1.U
  }
  warmRst := warmRstCtr =/= 0.U

  // ── SdramBackend: bridges MemoryController ↔ SdramController ─────────────
  val sdramBackend = withClockAndReset(sysClock, pllRst) {
    Module(new SdramBackend(SOC_MHZ))
  }

  // Mux backend: FlashBootLoader during boot, MemoryController after boot_done
  val bootDone = flashBoot.io.boot_done

  // → SdramBackend inputs (mux: bootloader before boot_done, MemoryController after)
  sdramBackend.io.backend.addrIn     := Mux(bootDone, mem.io.backend.addrIn,     flashBoot.io.backend.addrIn)
  sdramBackend.io.backend.dataIn     := Mux(bootDone, mem.io.backend.dataIn,     flashBoot.io.backend.dataIn)
  sdramBackend.io.backend.byteEnIn   := Mux(bootDone, mem.io.backend.byteEnIn,   flashBoot.io.backend.byteEnIn)
  // During boot the bootloader owns the backend.  The cold path never reads, but
  // the warm-reload path DOES (scratch→0 copy), so route flashBoot's startRead.
  sdramBackend.io.backend.startRead  := Mux(bootDone, mem.io.backend.startRead,  flashBoot.io.backend.startRead)
  sdramBackend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
  sdramBackend.io.backend.lenIn      := Mux(bootDone, mem.io.backend.lenIn,      flashBoot.io.backend.lenIn)

  // → MemoryController (only active after boot_done)
  mem.io.backend.dataOut := Mux(bootDone, sdramBackend.io.backend.dataOut, 0.U)
  mem.io.backend.accept  := Mux(bootDone, sdramBackend.io.backend.accept,  false.B)
  mem.io.backend.done    := Mux(bootDone, sdramBackend.io.backend.done,    false.B)
  mem.io.backend.busy    := Mux(bootDone, sdramBackend.io.backend.busy,    false.B)

  // → FlashBootLoader (single-word; never bursts, so accept is unused there)
  flashBoot.io.backend.dataOut := sdramBackend.io.backend.dataOut
  flashBoot.io.backend.accept  := Mux(!bootDone, sdramBackend.io.backend.accept, false.B)
  flashBoot.io.backend.done    := Mux(!bootDone, sdramBackend.io.backend.done, false.B)
  flashBoot.io.backend.busy    := Mux(!bootDone, sdramBackend.io.backend.busy, false.B)

  // ── SDRAM physical pin wiring ──────────────────────────────────────────────
  val pins = sdramBackend.io.sdramPins
  sdram_cke  := pins.cke
  sdram_csn  := pins.cs_n
  sdram_wen  := pins.we_n
  sdram_rasn := pins.ras_n
  sdram_casn := pins.cas_n
  sdram_a    := pins.addr
  sdram_ba   := pins.ba
  sdram_dqm  := pins.dqm

  // Bidirectional DQ: one BB per bit
  val dqIn = Wire(Vec(16, Bool()))
  for (i <- 0 until 16) {
    val bb = Module(new Ecp5BiDirBuf())
    bb.T := !pins.dq_oe
    bb.I := pins.dq_out(i)
    dqIn(i) := bb.O
    attach(sdram_d(i), bb.B)
  }
  pins.dq_in := dqIn.asUInt

  // ── Peripherals ───────────────────────────────────────────────────────────

  // DEBUG: hardware bypass UART — sends 'H' at 115200 from 125 MHz PLL.
  // Set to true to verify pin wiring without any CPU involvement.
  val DEBUG_UART_BYPASS = false

  if (DEBUG_UART_BYPASS) {
    val bypassUart = withClockAndReset(sysClock, pllRst) {
      val CLKS = (125000000 / 115200)   // 1085
      val baud = RegInit(0.U(11.W))
      val bitIdx = RegInit(0.U(4.W))   // 0=idle, 1=start, 2-9=data, 10=stop
      val gap = RegInit(0.U(24.W))
      val tx = RegInit(true.B)
      val data = "h48".U(8.W)   // 'H'

      when(bitIdx === 0.U) {
        tx := true.B
        gap := gap + 1.U
        when(gap(23)) {   // ~67ms gap
          gap := 0.U
          bitIdx := 1.U
          baud := 0.U
        }
      }.otherwise {
        baud := baud + 1.U
        when(baud === (CLKS - 1).U) {
          baud := 0.U
          when(bitIdx === 1.U) { tx := false.B }            // start bit
          .elsewhen(bitIdx <= 9.U) { tx := data(bitIdx - 2.U) } // data bits
          .otherwise { tx := true.B }                        // stop bit
          when(bitIdx === 10.U) { bitIdx := 0.U }
          .otherwise { bitIdx := bitIdx + 1.U }
        }
      }
      tx
    }
    ftdi_rxd := bypassUart
  } else {
    ftdi_rxd := uo_out_val(6)
  }



  // ── HDMI Scanout: enable + front-buffer select ────────────────────────────
  scanout.io.enable   := true.B
  scanout.io.frontBuf := fbSelectReg
  // Firmware-programmed framebuffer bases (single source of truth, no drift).
  scanout.io.fbBase   := scanoutFbBase0
  scanout.io.fbBase1  := scanoutFbBase1

  // ── VGA timing (25 MHz pixel clock = sysClock directly) ───────────────────
  // At 25 MHz SoC clock, every cycle IS a pixel tick — no divider needed.
  val tick25 = true.B
  val hCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }
  val vCount = withClockAndReset(sysClock, pllRst) { RegInit(0.U(10.W)) }
  val hTotal = 800.U;  val vTotal = 525.U
  val hActive = 640.U; val vActive = 480.U
  val hFront = 16.U;   val hSync = 96.U
  val vFront = 10.U;   val vSync = 2.U
  withClockAndReset(sysClock, pllRst) {
    when(hCount === hTotal - 1.U) {
      hCount := 0.U
      when(vCount === vTotal - 1.U) { vCount := 0.U }
      .otherwise { vCount := vCount + 1.U }
    } .otherwise { hCount := hCount + 1.U }
  }
  val de    = (hCount < hActive) && (vCount < vActive)
  val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync))
  val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync))
  scanout.io.hCount := hCount; scanout.io.vCount := vCount
  scanout.io.de := de; scanout.io.tick25 := tick25

  // ── CDC: latch RGB8 + sync from 25 MHz → 125 MHz ─────────────────────────
  // Pixel data changes once per 25 MHz cycle = 5× 125 MHz cycles.
  // Single register stage is safe (data is stable for 5 fast clocks).
  val hdmiRst = !pllLocked
  val hdmiRed   = withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.red) }
  val hdmiGreen = withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.green) }
  val hdmiBlue  = withClockAndReset(hdmiClock, hdmiRst) { RegNext(scanout.io.blue) }
  val hdmiHsync = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hsync) }
  val hdmiVsync = withClockAndReset(hdmiClock, hdmiRst) { RegNext(vsync) }
  val hdmiDe    = withClockAndReset(hdmiClock, hdmiRst) { RegNext(de) }

  // tick25 in the 125 MHz domain: fires every 5th cycle
  val hdmiCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(3.W)) }
  val hdmiTick25 = (hdmiCount === 4.U)
  withClockAndReset(hdmiClock, hdmiRst) {
    when(hdmiTick25) { hdmiCount := 0.U } .otherwise { hdmiCount := hdmiCount + 1.U }
  }

  // ── TMDS Encoders + Serializers (125 MHz domain) ─────────────────────────
  val encB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encB.io.en := hdmiTick25; encB.io.data := hdmiBlue
  encB.io.c := Cat(hdmiVsync, hdmiHsync); encB.io.de := hdmiDe
  val encG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encG.io.en := hdmiTick25; encG.io.data := hdmiGreen
  encG.io.c := 0.U; encG.io.de := hdmiDe
  val encR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
  encR.io.en := hdmiTick25; encR.io.data := hdmiRed
  encR.io.c := 0.U; encR.io.de := hdmiDe
  // TmdsEncoder has 1 pipeline stage (q_m_reg); delay serializer load by 1 cycle.
  val hdmiTick25D1 = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hdmiTick25, false.B) }
  val serB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serB.io.en := hdmiTick25D1; serB.io.tmds := encB.io.tmds
  val serG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serG.io.en := hdmiTick25D1; serG.io.tmds := encG.io.tmds
  val serR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serR.io.en := hdmiTick25D1; serR.io.tmds := encR.io.tmds
  val serClk = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
  serClk.io.en := hdmiTick25D1; serClk.io.tmds := "b0000011111".U
  gpdi_dp := Cat(serClk.io.out, serR.io.out, serG.io.out, serB.io.out)

  // ── LEDs: max debug ────────────────────────────────────────────────────────
  led := Cat(pllLocked, bootDone,
             flashBoot.io.debug_state,
             sdramBackend.io.backend.busy,
             uo_out_val(6))
}

// ── Pin constraints ────────────────────────────────────────────────────────

object ULX3SPins {
  case class PinDef(name: String, site: String, pull: String = "NONE",
                    ioType: String = "LVCMOS33", drive: Int = 4)

  val pins: Seq[PinDef] = Seq(
    PinDef("clk_25mhz", "G2",  pull = "NONE", drive = 4),
    PinDef("rst_n",      "D6",  pull = "UP",   drive = 4),

    // Flash (clock via USRMCLK — no pin needed for flash_clk)
    PinDef("flash_csn",  "R2",  pull = "UP"),
    PinDef("flash_mosi", "W2",  pull = "UP"),
    PinDef("flash_miso", "V2",  pull = "UP"),

    // SDRAM
    PinDef("sdram_clk",    "F19", drive = 8),
    PinDef("sdram_cke",    "F20"),
    PinDef("sdram_csn",    "P20"),
    PinDef("sdram_wen",    "T20"),
    PinDef("sdram_rasn",   "R20"),
    PinDef("sdram_casn",   "T19"),
    PinDef("sdram_a[0]",   "M20"), PinDef("sdram_a[1]",  "M19"),
    PinDef("sdram_a[2]",   "L20"), PinDef("sdram_a[3]",  "L19"),
    PinDef("sdram_a[4]",   "K20"), PinDef("sdram_a[5]",  "K19"),
    PinDef("sdram_a[6]",   "K18"), PinDef("sdram_a[7]",  "J20"),
    PinDef("sdram_a[8]",   "J19"), PinDef("sdram_a[9]",  "H20"),
    PinDef("sdram_a[10]",  "N19"), PinDef("sdram_a[11]", "G20"),
    PinDef("sdram_a[12]",  "G19"),
    PinDef("sdram_ba[0]",  "P19"), PinDef("sdram_ba[1]", "N20"),
    PinDef("sdram_dqm[0]", "U19"), PinDef("sdram_dqm[1]","E20"),
    PinDef("sdram_d_0",   "J16"), PinDef("sdram_d_1",  "L18"),
    PinDef("sdram_d_2",   "M18"), PinDef("sdram_d_3",  "N18"),
    PinDef("sdram_d_4",   "P18"), PinDef("sdram_d_5",  "T18"),
    PinDef("sdram_d_6",   "T17"), PinDef("sdram_d_7",  "U20"),
    PinDef("sdram_d_8",   "E19"), PinDef("sdram_d_9",  "D20"),
    PinDef("sdram_d_10",  "D19"), PinDef("sdram_d_11", "C20"),
    PinDef("sdram_d_12",  "E18"), PinDef("sdram_d_13", "F18"),
    PinDef("sdram_d_14",  "J18"), PinDef("sdram_d_15", "J17"),

    // UART
    PinDef("ftdi_rxd",  "L4",  pull = "UP"),
    PinDef("ftdi_txd",  "M1",  pull = "UP"),

    // LEDs
    PinDef("led[0]", "B2"), PinDef("led[1]", "C2"),
    PinDef("led[2]", "C1"), PinDef("led[3]", "D2"),
    PinDef("led[4]", "D1"), PinDef("led[5]", "E2"),
    PinDef("led[6]", "E1"), PinDef("led[7]", "H3"),

    // Buttons
    PinDef("btn[0]", "R1",  pull = "DOWN"), PinDef("btn[1]", "T1",  pull = "DOWN"),
    PinDef("btn[2]", "R18", pull = "DOWN"), PinDef("btn[3]", "V1",  pull = "DOWN"),
    PinDef("btn[4]", "U1",  pull = "DOWN"), PinDef("btn[5]", "H16", pull = "DOWN"),
    // GPDI (HDMI)
    PinDef("gpdi_dp[0]", "A16", ioType = "LVCMOS33D"),
    PinDef("gpdi_dp[1]", "A14", ioType = "LVCMOS33D"),
    PinDef("gpdi_dp[2]", "A12", ioType = "LVCMOS33D"),
    PinDef("gpdi_dp[3]", "A17", ioType = "LVCMOS33D"),
  )

  // ── Wafer.space Borg-only bridge link pins (BorgExternal, rungs B/C) ──────
  // Sites from ulx3s_v20.lpf's gp[]/gn[] table, confirmed applicable to this
  // board's actual v3.0.8 hardware revision (see that file's own comment:
  // "wifi lines shared with GP,GN on v3.0.x"). gp/gn[11-13] are skipped --
  // shared with onboard WiFi GPIO on v3.0.x, per that comment. 40 signals,
  // mirroring BorgLinkMasterIO's directions exactly (see the dn_d/up_d IO
  // block's doc in ulx3s_top): our own inputs (reading a disconnected far
  // side, or no far side at all before a real ASIC exists) default DOWN, to
  // fail the link's own odd-parity check by construction -- same intent as
  // chip_core.sv's bidir_pd on the ASIC side. Our own outputs don't need a
  // pull since they're always driven.
  //
  // Deliberately paired one dn_* signal with its up_* counterpart per physical
  // pin number (GP = the dn_/far_link_up half, GN = the matching up_ half) --
  // NOT grouped by which gp[]/gn[] index the site table happens to assign, so
  // each pair sits on one pin's own two rows and the map reads as a table
  // rather than a scatter.
  //
  // This layout is a leftover convenience from when BorgExternal was expected
  // to serve rung B by jumpering GP to GN. It cannot (see the far_link_up doc
  // in ulx3s_top: master-only, and the training precondition self-deadlocks),
  // and rung B is BorgPadLoop instead. For BorgExternal's actual use -- rung C,
  // far side on a second board -- the pairing is cosmetic: each of these
  // signals runs to its opposite number on the other board, not to its
  // neighbour here.
  // GP is the OUTER row (toward the board edge) and GN is the INNER row
  // (toward the crystal/buttons/chips) -- hardware-confirmed 2026-09-03 via
  // fpga/ulx3s/debug/pin_loopback_test.v (the opposite row guess failed on
  // real hardware; this one didn't).
  val linkExternalPins: Seq[PinDef] = Seq(
    // J1 pins 0-10 (11 pairs): dn_d[0..10] / up_d[0..10]. Pins 11-13 skipped
    // (WiFi-shared).
    PinDef("dn_d[0]",  "B11", pull = "NONE"), PinDef("up_d[0]",  "C11", pull = "DOWN"),
    PinDef("dn_d[1]",  "A10", pull = "NONE"), PinDef("up_d[1]",  "A11", pull = "DOWN"),
    PinDef("dn_d[2]",  "A9",  pull = "NONE"), PinDef("up_d[2]",  "B10", pull = "DOWN"),
    PinDef("dn_d[3]",  "B9",  pull = "NONE"), PinDef("up_d[3]",  "C10", pull = "DOWN"),
    PinDef("dn_d[4]",  "A7",  pull = "NONE"), PinDef("up_d[4]",  "A8",  pull = "DOWN"),
    PinDef("dn_d[5]",  "C8",  pull = "NONE"), PinDef("up_d[5]",  "B8",  pull = "DOWN"),
    PinDef("dn_d[6]",  "C6",  pull = "NONE"), PinDef("up_d[6]",  "C7",  pull = "DOWN"),
    PinDef("dn_d[7]",  "A6",  pull = "NONE"), PinDef("up_d[7]",  "B6",  pull = "DOWN"),
    PinDef("dn_d[8]",  "A4",  pull = "NONE"), PinDef("up_d[8]",  "A5",  pull = "DOWN"),
    PinDef("dn_d[9]",  "A2",  pull = "NONE"), PinDef("up_d[9]",  "B1",  pull = "DOWN"),
    PinDef("dn_d[10]", "C4",  pull = "NONE"), PinDef("up_d[10]", "B4",  pull = "DOWN"),

    // J2 pins 14-22 (9 pairs): dn_d[11..15]/up_d[11..15], dn_v/up_v,
    // dn_p/up_p, dn_cred/up_cred, far_link_up. gn[22] and pins 23-27 spare.
    PinDef("dn_d[11]", "U18", pull = "NONE"), PinDef("up_d[11]", "U17", pull = "DOWN"),
    PinDef("dn_d[12]", "N17", pull = "NONE"), PinDef("up_d[12]", "P16", pull = "DOWN"),
    PinDef("dn_d[13]", "N16", pull = "NONE"), PinDef("up_d[13]", "M17", pull = "DOWN"),
    PinDef("dn_d[14]", "L16", pull = "NONE"), PinDef("up_d[14]", "L17", pull = "DOWN"),
    PinDef("dn_d[15]", "H18", pull = "NONE"), PinDef("up_d[15]", "H17", pull = "DOWN"),
    PinDef("dn_v",     "F17", pull = "NONE"), PinDef("up_v",     "G18", pull = "DOWN"),
    PinDef("dn_p",     "D18", pull = "NONE"), PinDef("up_p",     "E17", pull = "DOWN"),
    PinDef("dn_cred",  "C18", pull = "DOWN"), PinDef("up_cred",  "D17", pull = "NONE"),
    PinDef("far_link_up",  "B15", pull = "DOWN"),
  )

  // ── Rung B pad-loop pin map (BorgPadLoop) ────────────────────────────────
  // 23 pins, one logical link wire each, GP = the FPGA's driving pad and GN =
  // its receiving pad -- so the ribbon bridges each pin's own two rows, the
  // same physical pattern as the BorgExternal loom above (GP outer / GN inner,
  // hardware-confirmed). Sites are gp[]/gn[] from ulx3s_v20.lpf; gp/gn[11-13]
  // are skipped as WiFi-shared on this board revision.
  //
  // Inputs pull DOWN so a missing or broken jumper reads 0: that is the safe
  // failure, since v/p and linkUp all mean "nothing is happening" when low.
  val padLoopPins: Seq[PinDef] = Seq(
    // J1 pins 0-10: master -> cable -> slave.
    PinDef("pl_dn_d_out[0]", "B11", pull = "NONE"), PinDef("pl_dn_d_in[0]", "C11", pull = "DOWN"),
    PinDef("pl_dn_d_out[1]", "A10", pull = "NONE"), PinDef("pl_dn_d_in[1]", "A11", pull = "DOWN"),
    PinDef("pl_dn_d_out[2]", "A9",  pull = "NONE"), PinDef("pl_dn_d_in[2]", "B10", pull = "DOWN"),
    PinDef("pl_dn_d_out[3]", "B9",  pull = "NONE"), PinDef("pl_dn_d_in[3]", "C10", pull = "DOWN"),
    PinDef("pl_dn_d_out[4]", "A7",  pull = "NONE"), PinDef("pl_dn_d_in[4]", "A8",  pull = "DOWN"),
    PinDef("pl_dn_d_out[5]", "C8",  pull = "NONE"), PinDef("pl_dn_d_in[5]", "B8",  pull = "DOWN"),
    PinDef("pl_dn_d_out[6]", "C6",  pull = "NONE"), PinDef("pl_dn_d_in[6]", "C7",  pull = "DOWN"),
    PinDef("pl_dn_d_out[7]", "A6",  pull = "NONE"), PinDef("pl_dn_d_in[7]", "B6",  pull = "DOWN"),
    PinDef("pl_dn_v_out",    "A4",  pull = "NONE"), PinDef("pl_dn_v_in",    "A5",  pull = "DOWN"),
    PinDef("pl_dn_p_out",    "A2",  pull = "NONE"), PinDef("pl_dn_p_in",    "B1",  pull = "DOWN"),
    PinDef("pl_upcred_out",  "C4",  pull = "NONE"), PinDef("pl_upcred_in",  "B4",  pull = "DOWN"),

    // J2 pins 14-25: slave -> cable -> master.
    PinDef("pl_up_d_out[0]", "U18", pull = "NONE"), PinDef("pl_up_d_in[0]", "U17", pull = "DOWN"),
    PinDef("pl_up_d_out[1]", "N17", pull = "NONE"), PinDef("pl_up_d_in[1]", "P16", pull = "DOWN"),
    PinDef("pl_up_d_out[2]", "N16", pull = "NONE"), PinDef("pl_up_d_in[2]", "M17", pull = "DOWN"),
    PinDef("pl_up_d_out[3]", "L16", pull = "NONE"), PinDef("pl_up_d_in[3]", "L17", pull = "DOWN"),
    PinDef("pl_up_d_out[4]", "H18", pull = "NONE"), PinDef("pl_up_d_in[4]", "H17", pull = "DOWN"),
    PinDef("pl_up_d_out[5]", "F17", pull = "NONE"), PinDef("pl_up_d_in[5]", "G18", pull = "DOWN"),
    PinDef("pl_up_d_out[6]", "D18", pull = "NONE"), PinDef("pl_up_d_in[6]", "E17", pull = "DOWN"),
    PinDef("pl_up_d_out[7]", "C18", pull = "NONE"), PinDef("pl_up_d_in[7]", "D17", pull = "DOWN"),
    PinDef("pl_up_v_out",    "B15", pull = "NONE"), PinDef("pl_up_v_in",    "C15", pull = "DOWN"),
    PinDef("pl_up_p_out",    "B17", pull = "NONE"), PinDef("pl_up_p_in",    "C17", pull = "DOWN"),
    PinDef("pl_dncred_out",  "C16", pull = "NONE"), PinDef("pl_dncred_in",  "D16", pull = "DOWN"),
    PinDef("pl_linkup_out",  "D14", pull = "NONE"), PinDef("pl_linkup_in",  "E14", pull = "DOWN"),
  )

  /** SW4 alone -- rung B consumes only link_fast; dbg_sel/link_narrow are
    * BorgExternal's reserved lane-map positions and no RTL reads them. */
  val linkFastPin: Seq[PinDef] = Seq(PinDef("link_fast", "E7", pull = "DOWN"))

  // Control straps -- onboard DIP switches SW1-4, not GP/GN pins. Sites +
  // PULLMODE=DOWN from ulx3s_v20.lpf's "sw[]" block.
  val dipSwitchPins: Seq[PinDef] = Seq(
    PinDef("dbg_sel[0]",   "E8", pull = "DOWN"),
    PinDef("dbg_sel[1]",   "D8", pull = "DOWN"),
    PinDef("link_narrow",  "D7", pull = "DOWN"),
    PinDef("link_fast",    "E7", pull = "DOWN"),
  )

  def emitLPF(path: String, extraPins: Seq[PinDef] = Seq()): Unit = {
    val writer = new java.io.PrintWriter(path)
    writer.println("# ULX3S (ECP5-85K) pin constraints")
    writer.println("# Sites from ulx3s_v20.lpf — https://github.com/emard/ulx3s")
    writer.println()
    // Required for USRMCLK: hand flash SPI clock control to user logic after boot.
    // CONFIG_MODE=SPI_SERIAL: prevents ECP5 from leaving flash in QPI mode after boot.
    // Without this, the flash ignores all standard 1-bit SPI commands from user logic.
    writer.println("SYSCONFIG CONFIG_IOVOLTAGE=3.3 COMPRESS_CONFIG=ON MCCLK_FREQ=2.4 MASTER_SPI_PORT=DISABLE SLAVE_SPI_PORT=DISABLE SLAVE_PARALLEL_PORT=DISABLE CONFIG_MODE=SPI_SERIAL;");
    writer.println()
    writer.println("BLOCK RESETPATHS;")
    writer.println("BLOCK ASYNCPATHS;")
    writer.println()
    writer.println(s"""LOCATE COMP "clk_25mhz" SITE "G2";""")
    writer.println(s"""IOBUF  PORT "clk_25mhz" PULLMODE=NONE IO_TYPE=LVCMOS33;""")
    writer.println(s"""FREQUENCY PORT "clk_25mhz" 25 MHZ;""")
    writer.println()
    for (p <- pins if p.name != "clk_25mhz") {
      writer.println(f"""LOCATE COMP "${p.name}" SITE "${p.site}";""")
      writer.println(f"""IOBUF  PORT "${p.name}" PULLMODE=${p.pull} IO_TYPE=${p.ioType} DRIVE=${p.drive};""")
    }
    for (p <- extraPins) {
      writer.println(f"""LOCATE COMP "${p.name}" SITE "${p.site}";""")
      writer.println(f"""IOBUF  PORT "${p.name}" PULLMODE=${p.pull} IO_TYPE=${p.ioType} DRIVE=${p.drive};""")
    }
    writer.println()
    writer.close()
    println(s"Generated LPF: $path")
  }
}

/** Emit Verilog + LPF for the ULX3S target. */
object ULX3SMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** Rung A of the wafer.space Borg-only bridge's on-hardware ladder: same
  * board, same demo firmware, Borg reached only through the real link RTL
  * (BorgLinkMaster + BorgLinkSlave, "pins" are internal wires) instead of
  * directly. Separate emission target/output dir so the default ULX3SMain
  * (what the demo/talk bitstream ships) is completely unaffected -- this is
  * purely additive.
  */
object ULX3SLoopbackMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog_loopback"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz, borgModeOverride = BorgLoopback),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf")
}

/** Rungs B/C of the wafer.space Borg-only bridge's on-hardware ladder: the
  * link reaches real GP/GN pins (rung B: ribbon-cable loopback on this same
  * board; rung C: a second ULX3S running BorgOnlyTop across real
  * 74LVC8T245 level shifters) instead of BorgLoopback's internal wires.
  * Separate emission target/output dir, same as ULX3SLoopbackMain -- purely
  * additive, the default ULX3SMain demo bitstream is unaffected.
  */
object ULX3SExternalMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog_external"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz, borgModeOverride = BorgExternal),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf",
    extraPins = ULX3SPins.linkExternalPins ++ ULX3SPins.dipSwitchPins)
}

/** Rung B of the on-hardware ladder: the complete bridge (master + slave + a
  * real Borg, exactly like rung A), but with the master<->slave path routed out
  * through real pads and shorted back by a ribbon cable across J1/J2 -- adding
  * IO buffers, flight time and SSO to a topology rung A already proved.
  *
  * Not a variant of ULX3SExternalMain: that one elaborates the master alone, so
  * a loopback cable there feeds the master its own beats with no slave or Borg
  * to answer. See BorgMode's doc.
  */
object ULX3SPadLoopMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "125").toInt
  val targetDir = "out/ulx3s/verilog_padloop"
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new ulx3s_top(clockMhz, borgModeOverride = BorgPadLoop),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )

  ULX3SPins.emitLPF(s"$targetDir/ulx3s.lpf",
    extraPins = ULX3SPins.padLoopPins ++ ULX3SPins.linkFastPin)
}
