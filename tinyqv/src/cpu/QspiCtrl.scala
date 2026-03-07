// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class QspiController extends RawModule {
  override val desiredName = "qspi_controller"
  val io = IO(new QspiControllerIO)

  // FSM States
  object State extends ChiselEnum {
    val IDLE, CMD, ADDR, DUMMY1, DUMMY2, DATA, STALLED, STALL_RECOVER = Value
  }

  // Need a clk_pos signal for the negedge block
  val spi_clk_pos_wire = Wire(Bool())
  val spi_clk_use_neg_wire = Wire(Bool())

  withClockAndReset(io.clk, !io.rstn) {
    val fsm_state = RegInit(State.IDLE)
    val is_writing = RegInit(false.B)
    val addr = RegInit(0.U(24.W))
    val data = RegInit(0.U(8.W))
    val nibbles_remaining = RegInit(0.U(3.W))
    val delay_cycles_cfg = RegInit(0.U(2.W))
    val spi_clk_use_neg = RegInit(false.B)
    val spi_clk_pos = RegInit(false.B)
    val spi_in_buffer = RegInit(0.U(4.W))
    val read_cycles_count = RegInit(0.U(2.W))
    val stop_txn_reg = RegInit(false.B)
    val last_ram_a_sel = RegInit(true.B)
    val last_ram_b_sel = RegInit(true.B)

    // Registers for IO signals that were 'reg' in Verilog and assigned in posedge clk block
    val spi_data_oe_reg = RegInit(0.U(4.W))
    val spi_flash_select_reg = RegInit(true.B)
    val spi_ram_a_select_reg = RegInit(true.B)
    val spi_ram_b_select_reg = RegInit(true.B)
    val data_ready_reg = RegInit(false.B)
    val data_req_reg = RegInit(false.B)

    spi_clk_pos_wire := spi_clk_pos
    spi_clk_use_neg_wire := spi_clk_use_neg

    // Configuration latched during reset
    when (!io.rstn) {
      delay_cycles_cfg := io.spi_data_in(1, 0)
      spi_clk_use_neg := io.spi_data_in(2)
    }

    val stop_txn_now = stop_txn_reg || (io.stop_txn && (!is_writing || spi_clk_pos))
    stop_txn_reg := io.stop_txn && !stop_txn_now

    val ram_a_block = (last_ram_a_sel === false.B) && io.addr_in(24, 23) === 2.U
    val ram_b_block = (last_ram_b_sel === false.B) && io.addr_in(24, 23) === 3.U

    io.busy := fsm_state =/= State.IDLE

    when (stop_txn_now) {
      fsm_state := State.IDLE
      is_writing := false.B
      nibbles_remaining := 0.U
      spi_clk_pos := false.B
      spi_data_oe_reg := 0.U
      spi_flash_select_reg := true.B
      spi_ram_a_select_reg := true.B
      spi_ram_b_select_reg := true.B
      data_ready_reg := false.B
      data_req_reg := false.B
      read_cycles_count := 0.U
    } .otherwise {
      data_ready_reg := false.B
      data_req_reg := false.B
      when (fsm_state === State.IDLE) {
        when ((io.start_read || io.start_write) && !ram_a_block && !ram_b_block) {
          fsm_state := Mux(io.addr_in(24), State.CMD, State.ADDR)
          is_writing := !io.start_read && io.addr_in(24)
          nibbles_remaining := Mux(io.addr_in(24), 1.U, 5.U)
          spi_data_oe_reg := "hf".U
          spi_clk_pos := false.B
          spi_flash_select_reg := io.addr_in(24)
          spi_ram_a_select_reg := io.addr_in(24, 23) =/= 2.U
          spi_ram_b_select_reg := io.addr_in(24, 23) =/= 3.U
        }
      } .otherwise {
        when (read_cycles_count === 0.U) {
          read_cycles_count := 1.U
        } .otherwise {
          read_cycles_count := read_cycles_count - 1.U
        }

        when (fsm_state === State.STALLED) {
          spi_clk_pos := false.B
          when (!io.stall_txn && !read_cycles_count(1)) {
            data_ready_reg := !is_writing
            when (is_writing) {
              fsm_state := State.DATA
              read_cycles_count := 0.U
            } .otherwise {
              fsm_state := Mux(delay_cycles_cfg(1) === 0.U, State.DATA, State.STALL_RECOVER)
              read_cycles_count := Cat(0.U(1.W), delay_cycles_cfg(0))
            }
          }
        } .otherwise {
          spi_clk_pos := !spi_clk_pos
          val cycle_done = Mux(
            (fsm_state === State.DATA && !is_writing) || fsm_state === State.STALL_RECOVER,
            read_cycles_count === 0.U,
            spi_clk_pos
          )

          when (cycle_done) {
            when (nibbles_remaining === 0.U) {
              when (fsm_state === State.DATA || fsm_state === State.STALL_RECOVER) {
                data_ready_reg := !is_writing && !io.stall_txn
                nibbles_remaining := 1.U
                when (io.stall_txn) {
                  fsm_state := State.STALLED
                  read_cycles_count := delay_cycles_cfg | 1.U
                } .otherwise {
                  fsm_state := State.DATA
                }
              } .otherwise {
                switch (fsm_state) {
                  is (State.CMD) {
                    fsm_state := State.ADDR
                    nibbles_remaining := 5.U
                  }
                  is (State.ADDR) {
                    when (is_writing) {
                      fsm_state := State.DATA
                      nibbles_remaining := 1.U
                    } .elsewhen (spi_flash_select_reg) {
                      fsm_state := State.DUMMY2
                      spi_data_oe_reg := 0.U
                      nibbles_remaining := 3.U
                    } .otherwise {
                      fsm_state := State.DUMMY1
                      nibbles_remaining := 1.U
                    }
                  }
                  is (State.DUMMY1) {
                    fsm_state := State.DUMMY2
                    spi_data_oe_reg := 0.U
                    nibbles_remaining := 3.U
                  }
                  is (State.DUMMY2) {
                    fsm_state := State.DATA
                    nibbles_remaining := 1.U
                    read_cycles_count := delay_cycles_cfg
                  }
                }
              }
            } .otherwise {
              when (fsm_state === State.STALL_RECOVER) {
                fsm_state := State.DATA
              }
              nibbles_remaining := nibbles_remaining - 1.U
            }
          } .otherwise {
            data_req_reg := is_writing && (fsm_state === State.DATA) && nibbles_remaining === 0.U
          }
        }
      }
    }

    // Address shifting
    when (fsm_state === State.IDLE && (io.start_read || io.start_write)) {
      addr := io.addr_in(23, 0)
    } .elsewhen (fsm_state === State.ADDR && spi_clk_pos) {
      addr := Cat(addr(19, 0), 0.U(4.W))
    }

    // Data shifting
    when (is_writing) {
      when (fsm_state === State.STALLED) {
        data := io.data_in
      } .elsewhen (spi_clk_pos) {
        when (nibbles_remaining === 0.U) {
          data := io.data_in
        } .elsewhen (fsm_state === State.DATA) {
          data := Cat(data(3, 0), io.spi_data_in)
        }
      }
    } .otherwise {
      when (read_cycles_count === 0.U && fsm_state === State.DATA) {
        data := Cat(data(3, 0), io.spi_data_in)
      } .elsewhen (read_cycles_count === 0.U && fsm_state === State.STALL_RECOVER) {
        data := Cat(data(3, 0), spi_in_buffer)
      } .elsewhen (read_cycles_count === 2.U && fsm_state === State.STALLED) {
        spi_in_buffer := io.spi_data_in
      }
    }

    // Output assignments
    io.spi_data_out := 10.U // Default 0xAh
    switch (fsm_state) {
      is (State.CMD) {
        when (is_writing) {
          io.spi_data_out := Mux(nibbles_remaining(0), 0.U, 2.U) // 02h
        } .otherwise {
          io.spi_data_out := Mux(nibbles_remaining(0), 0.U, 11.U) // 0Bh
        }
      }
      is (State.ADDR) {
        io.spi_data_out := addr(23, 20)
      }
      is (State.DUMMY1) {
        io.spi_data_out := 10.U // 0xAh
      }
      is (State.DATA) {
        io.spi_data_out := Mux(is_writing, data(7, 4), 15.U) // 0xFh
      }
    }

    last_ram_a_sel := spi_ram_a_select_reg
    last_ram_b_sel := spi_ram_b_select_reg

    io.data_out := data
    io.spi_data_oe := spi_data_oe_reg
    io.spi_flash_select := spi_flash_select_reg
    io.spi_ram_a_select := spi_ram_a_select_reg
    io.spi_ram_b_select := spi_ram_b_select_reg
    io.data_ready := data_ready_reg
    io.data_req := data_req_reg
  }

  // SPI clock generation on negedge
  val spi_clk_neg = withClock((!io.clk.asBool).asClock) {
    RegNext(spi_clk_pos_wire)
  }
  io.spi_clk_out := Mux(spi_clk_use_neg_wire, spi_clk_neg, spi_clk_pos_wire)
}
