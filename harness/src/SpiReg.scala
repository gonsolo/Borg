// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package harness

import chisel3._
import chisel3.util._

class SpiRegIO(addrW: Int, regW: Int) extends Bundle {
  val ena = Input(Bool())
  val spi_mosi = Input(Bool())
  val spi_miso = Output(Bool())
  val spi_clk = Input(Bool())
  val spi_cs_n = Input(Bool())

  val reg_addr = Output(UInt(addrW.W))
  val reg_data_i = Input(UInt(regW.W))
  val reg_data_o = Output(UInt(regW.W))
  val reg_addr_v = Output(Bool())
  val reg_data_i_dv = Input(Bool())
  val reg_data_o_dv = Output(Bool())
  val reg_rw = Output(Bool())
  val txn_width = Output(UInt(2.W))
}

class SpiReg(val addrW: Int = 6, val regW: Int = 32) extends Module {
  val io = IO(new SpiRegIO(addrW, regW))

  // Start of frame - negedge of spi_cs_n
  val sof_det = Module(new FallingEdgeDetector)
  sof_det.io.ena := io.ena
  sof_det.io.data := io.spi_cs_n
  val sof = sof_det.io.neg_edge

  // End of frame - posedge of spi_cs_n
  val eof_det = Module(new RisingEdgeDetector)
  eof_det.io.ena := io.ena
  eof_det.io.data := io.spi_cs_n
  val eof = eof_det.io.pos_edge

  // SPI Clock edges
  val spi_clk_pos_det = Module(new RisingEdgeDetector)
  spi_clk_pos_det.io.ena := io.ena
  spi_clk_pos_det.io.data := io.spi_clk
  val spi_clk_pos = spi_clk_pos_det.io.pos_edge

  val spi_clk_neg_det = Module(new FallingEdgeDetector)
  spi_clk_neg_det.io.ena := io.ena
  spi_clk_neg_det.io.data := io.spi_clk
  val spi_clk_neg = spi_clk_neg_det.io.neg_edge

  // Gated by chip select
  val spi_clk_pos_gated = spi_clk_pos && !io.spi_cs_n
  val spi_clk_neg_gated = spi_clk_neg && !io.spi_cs_n

  // Assume mode 00
  val spi_data_sample = spi_clk_pos_gated
  val spi_data_change = spi_clk_neg_gated

  // FSM States
  object State extends ChiselEnum {
    val sIdle, sAddr, sCmd, sRxData, sTxLoad, sTxData = Value
  }
  import State._

  val state = RegInit(sIdle)
  val nextState = WireDefault(state)

  val bufferCounter = RegInit(0.U(6.W))
  val txBufferLoad = WireDefault(false.B)
  val sampleAddr = WireDefault(false.B)
  val sampleData = WireDefault(false.B)

  when(io.ena) {
    state := nextState
  }

  // Next State Logic
  switch(state) {
    is(sIdle) {
      when(sof) { nextState := sAddr }
    }
    is(sAddr) {
      when(bufferCounter === regW.U) {
        sampleAddr := true.B
        nextState := sCmd
      } .elsewhen(eof) {
        nextState := sIdle
      }
    }
    is(sCmd) {
      when(!io.reg_rw) {
        nextState := sTxLoad
      } .elsewhen(io.reg_rw) {
        nextState := sRxData
      } .elsewhen(eof) {
        nextState := sIdle
      }
    }
    is(sRxData) {
      when(bufferCounter === regW.U) {
        sampleData := true.B
        nextState := sIdle
      } .elsewhen(eof) {
        nextState := sIdle
      }
    }
    is(sTxLoad) {
      txBufferLoad := true.B
      when(io.reg_data_i_dv) {
        nextState := sTxData
      }
    }
    is(sTxData) {
      when(bufferCounter === regW.U) {
        nextState := sIdle
      } .elsewhen(eof) {
        nextState := sIdle
      }
    }
  }

  // Transaction buffer
  val txnBuffer = RegInit(0.U(regW.W))

  when(io.ena) {
    switch(state) {
      is(sIdle) {
        txnBuffer := 0.U
      }
      is(sTxLoad) {
        txnBuffer := io.reg_data_i
      }
      is(sTxData) {
        when(spi_data_change && bufferCounter =/= 0.U) {
          txnBuffer := Cat(txnBuffer(regW - 2, 0), 0.U(1.W))
        }
      }
    }
    // Default action for states not handling load/shift out: shift in
    when(state =/= sIdle && state =/= sTxLoad && state =/= sTxData) {
       when(spi_data_sample) {
         txnBuffer := Cat(txnBuffer(regW - 2, 0), io.spi_mosi)
       }
    }
  }

  // Buffer counter logic
  when(io.ena) {
    when(bufferCounter === regW.U) {
      bufferCounter := 0.U
    } .elsewhen(spi_data_sample) {
      bufferCounter := bufferCounter + 1.U
    }
  }

  // Address and Command Registers
  val regAddr = RegInit(0.U(addrW.W))
  val regRw = RegInit(false.B)
  val txnWidth = RegInit(3.U(2.W))

  when(io.ena) {
    when(sampleAddr) {
      regAddr := txnBuffer(addrW - 1, 0)
      regRw := txnBuffer(regW - 1)
      txnWidth := txnBuffer(regW - 2, regW - 3)
    }
  }

  io.reg_addr := regAddr
  io.reg_rw := regRw
  io.txn_width := txnWidth
  io.reg_addr_v := txBufferLoad

  // Data output mapping
  io.reg_data_o := txnBuffer
  val dv = RegInit(false.B)

  when(io.ena) {
    dv := false.B
    when(sampleData) {
      dv := (true.B && regRw)
    }
  }
  io.reg_data_o_dv := dv

  // MISO out
  io.spi_miso := Mux(state === sTxData, txnBuffer(regW - 1), false.B)
}
