package soc

import chisel3._
import chisel3.util._
import memory.{Ecp5PllParams, Ecp5PllWrapper, SdramBackend, MemBackendIO}
import _root_.circt.stage.ChiselStage

class HdmiScanoutIO extends Bundle {
  val backend = new MemBackendIO
  val hCount = Input(UInt(10.W))
  val vCount = Input(UInt(10.W))
  val de     = Input(Bool())
  val tick25 = Input(Bool())
  val red   = Output(UInt(8.W))
  val green = Output(UInt(8.W))
  val blue  = Output(UInt(8.W))
}

class HdmiScanout extends Module {
  val io = IO(new HdmiScanoutIO)

  val fbWidth = 64.U
  val fbHeight = 64.U
  val startX = (640.U - fbWidth) / 2.U
  val startY = (480.U - fbHeight) / 2.U
  
  val inFbH = io.hCount >= startX && io.hCount < startX + fbWidth
  val inFbV = io.vCount >= startY && io.vCount < startY + fbHeight

  val lineBuffer = SyncReadMem(64, UInt(16.W))
  
  val nextV = Mux(io.vCount === 524.U, 0.U, io.vCount + 1.U)
  val fetchNextV = nextV >= startY && nextV < startY + fbHeight
  val triggerFetch = io.tick25 && io.hCount === 640.U && fetchNextV
  
  val fbBase = 0x100000.U(25.W)
  
  val sIdle :: sWaitBusy :: sReq :: sRead :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val wordCount = RegInit(0.U(7.W))

  val lineWordBase = (fbBase + ((nextV - startY) * fbWidth) * 2.U)(24, 1)

  io.backend.startRead  := (state === sReq)
  io.backend.startWrite := false.B
  io.backend.dataIn     := 0.U
  io.backend.byteEnIn   := "b11".U
  io.backend.addrIn     := lineWordBase + wordCount

  val wrPortAddr = WireDefault(0.U(6.W))
  val wrPortData = WireDefault(0.U(16.W))
  val wrPortEn   = WireDefault(false.B)

  when(wrPortEn) {
    lineBuffer.write(wrPortAddr, wrPortData)
  }

  when(state === sIdle) {
    when(triggerFetch) {
      state := sWaitBusy
      wordCount := 0.U
    }
  } .elsewhen(state === sWaitBusy) {
    when(!io.backend.busy) {
      state := sReq
    }
  } .elsewhen(state === sReq) {
    state := sRead
  } .otherwise {
    when(io.backend.done) {
      wrPortAddr := wordCount(5, 0)
      wrPortData := io.backend.dataOut
      wrPortEn   := true.B
      when(wordCount === 63.U) {
        state := sIdle
      } .otherwise {
        wordCount := wordCount + 1.U
        state := sReq
      }
    }
  }
  
  val rdAddr = io.hCount - startX
  val rdData = lineBuffer.read(rdAddr)
  
  val r5 = rdData(15, 11)
  val g6 = rdData(10, 5)
  val b5 = rdData(4, 0)
  
  val r8 = Cat(r5, r5(4, 2))
  val g8 = Cat(g6, g6(5, 4))
  val b8 = Cat(b5, b5(4, 2))
  
  io.red   := Mux(io.de && inFbH && inFbV, r8, 0.U)
  io.green := Mux(io.de && inFbH && inFbV, g8, 0.U)
  io.blue  := Mux(io.de && inFbH && inFbV, b8, 0.U)
}

class HdmiSdramTest extends RawModule {
  val clk_25mhz = IO(Input(Clock()))
  val rst_n     = IO(Input(Bool()))
  
  val gpdi_dp   = IO(Output(UInt(4.W)))
  val led       = IO(Output(UInt(8.W)))
  
  val sdram_clk  = IO(Output(Clock()))
  val sdram_cke  = IO(Output(Bool()))
  val sdram_csn  = IO(Output(Bool()))
  val sdram_wen  = IO(Output(Bool()))
  val sdram_rasn = IO(Output(Bool()))
  val sdram_casn = IO(Output(Bool()))
  val sdram_a    = IO(Output(UInt(13.W)))
  val sdram_ba   = IO(Output(UInt(2.W)))
  val sdram_dqm  = IO(Output(UInt(2.W)))
  val sdram_d    = IO(Vec(16, chisel3.experimental.Analog(1.W)))
  
  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = 125_000_000L,
    out1Hz = 125_000_000L, out1Deg = 90
  )))
  pll.io.clk_i := clk_25mhz
  val clk125 = pll.io.clk_o(0)
  val sdramClock = pll.io.clk_o(1)
  val locked = pll.io.locked
  
  sdram_clk := sdramClock
  
  withClockAndReset(clk125, !locked) {
    val sdramBackend = Module(new SdramBackend(125))
    val pins = sdramBackend.io.sdramPins
    sdram_cke  := pins.cke
    sdram_csn  := pins.cs_n
    sdram_wen  := pins.we_n
    sdram_rasn := pins.ras_n
    sdram_casn := pins.cas_n
    sdram_a    := pins.addr
    sdram_ba   := pins.ba
    sdram_dqm  := pins.dqm

    val dqIn = Wire(Vec(16, Bool()))
    for (i <- 0 until 16) {
      val bb = Module(new Ecp5BiDirBuf())
      bb.T := !pins.dq_oe
      bb.I := pins.dq_out(i)
      dqIn(i) := bb.O
      chisel3.experimental.attach(sdram_d(i), bb.B)
    }
    pins.dq_in := dqIn.asUInt
    
class BootFsmIO extends Bundle {
  val backend = new MemBackendIO
  val done    = Output(Bool())
}

    val bootFsm = Module(new Module {
      val io = IO(new BootFsmIO)
      val fbSize = (64 * 64 * 2).U
      val fbStart = 0x100000.U(25.W)
      val addr = RegInit(0x100000.U(25.W))
      val sIdle :: sReq :: sWriteWordWait :: sDone :: Nil = Enum(4)
      val state = RegInit(sIdle)

      io.backend.startRead  := false.B
      io.backend.startWrite := (state === sReq)
      io.backend.byteEnIn   := "b11".U
      io.backend.addrIn     := addr(24, 1)
      io.backend.dataIn     := 0xF800.U(16.W)

      io.done := (state === sDone)

      switch(state) {
        is(sIdle) {
          when(!io.backend.busy) {
             state := sReq
          }
        }
        is(sReq) {
          state := sWriteWordWait
        }
        is(sWriteWordWait) {
          when(io.backend.done) {
            when(addr >= fbStart + fbSize - 2.U) {
              state := sDone
            } .otherwise {
              addr := addr + 2.U
              state := sIdle
            }
          }
        }
      }
    })
    
    val scanout = Module(new HdmiScanout)
    
    val bootDone = bootFsm.io.done
    
    sdramBackend.io.backend.addrIn     := Mux(bootDone, scanout.io.backend.addrIn, bootFsm.io.backend.addrIn)
    sdramBackend.io.backend.dataIn     := Mux(bootDone, scanout.io.backend.dataIn, bootFsm.io.backend.dataIn)
    sdramBackend.io.backend.startRead  := Mux(bootDone, scanout.io.backend.startRead, bootFsm.io.backend.startRead)
    sdramBackend.io.backend.startWrite := Mux(bootDone, scanout.io.backend.startWrite, bootFsm.io.backend.startWrite)
    sdramBackend.io.backend.byteEnIn   := "b11".U
    // stopTxn removed
    
    scanout.io.backend.dataOut   := sdramBackend.io.backend.dataOut
    scanout.io.backend.done   := sdramBackend.io.backend.done
    // dataReady removed
    scanout.io.backend.busy      := sdramBackend.io.backend.busy
    
    bootFsm.io.backend.dataOut := sdramBackend.io.backend.dataOut
    bootFsm.io.backend.done    := Mux(!bootDone, sdramBackend.io.backend.done, false.B)
    bootFsm.io.backend.busy    := Mux(!bootDone, sdramBackend.io.backend.busy, false.B)
    
    val count = RegInit(0.U(3.W))
    val tick25 = (count === 4.U)
    when(tick25) { count := 0.U } .otherwise { count := count + 1.U }
    
    val hCount = RegInit(0.U(10.W))
    val vCount = RegInit(0.U(10.W))
    
    val hTotal = 800.U
    val vTotal = 525.U
    val hActive = 640.U
    val vActive = 480.U
    val hFront = 16.U
    val hSync = 96.U
    val vFront = 10.U
    val vSync = 2.U
    
    when(tick25) {
      when(hCount === hTotal - 1.U) {
        hCount := 0.U
        when(vCount === vTotal - 1.U) {
          vCount := 0.U
        } .otherwise {
          vCount := vCount + 1.U
        }
      } .otherwise {
        hCount := hCount + 1.U
      }
    }
    
    val de = (hCount < hActive) && (vCount < vActive)
    val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync))
    val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync))
    
    scanout.io.hCount := hCount
    scanout.io.vCount := vCount
    scanout.io.de     := de
    scanout.io.tick25 := tick25
    
    val encB = Module(new TmdsEncoder)
    encB.io.en   := tick25
    encB.io.data := scanout.io.blue
    encB.io.c    := Cat(vsync, hsync)
    encB.io.de   := de
    
    val encG = Module(new TmdsEncoder)
    encG.io.en   := tick25
    encG.io.data := scanout.io.green
    encG.io.c    := 0.U
    encG.io.de   := de
    
    val encR = Module(new TmdsEncoder)
    encR.io.en   := tick25
    encR.io.data := scanout.io.red
    encR.io.c    := 0.U
    encR.io.de   := de
    
    val serB = Module(new TmdsSerializer)
    serB.io.en   := tick25
    serB.io.tmds := encB.io.tmds
    
    val serG = Module(new TmdsSerializer)
    serG.io.en   := tick25
    serG.io.tmds := encG.io.tmds
    
    val serR = Module(new TmdsSerializer)
    serR.io.en   := tick25
    serR.io.tmds := encR.io.tmds
    
    val serClk = Module(new TmdsSerializer)
    serClk.io.en   := tick25
    serClk.io.tmds := "b0000011111".U
    
    gpdi_dp := Cat(serClk.io.out, serR.io.out, serG.io.out, serB.io.out)
    
    led := Cat(locked, bootDone, scanout.io.backend.busy, 0.U(5.W))
  }
}

object HdmiSdramTestMain extends App {
  val targetDir = sys.env.getOrElse("TARGET_DIR", "out/ulx3s/hdmi_sdram_test")
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new HdmiSdramTest(),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
