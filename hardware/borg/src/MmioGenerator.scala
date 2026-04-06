// Copyright Andreas Wendleder 2026
// CERN-OHL-S-2.0

package borg

import Instructions._

/** Code generator for firmware headers and Python constants bound to MmioMap. */
object MmioGenerator {
  import MmioMap._

  // --- Formatter Abstraction ---
  abstract class Emitter(val w: java.io.PrintWriter) {
    def comment(text: String): Unit
    def section(text: String): Unit = { w.println(); comment(s"--- $text ---") }
    def assign(name: String, value: Int, commentStr: String = ""): Unit
    def assignHex(name: String, value: Int, width: Int): Unit

    def emitInstrR(name: String, hexOp: String): Unit
    def emitInstrR4(name: String, hexOp: String): Unit
    def emitInstrR1(name: String, hexOp: String): Unit
    def emitInstr0(name: String, hexOp: String): Unit
  }

  class CEmitter(w: java.io.PrintWriter) extends Emitter(w) {
    w.println("#pragma once")
    w.println()

    def comment(text: String): Unit = w.println(s"// $text")
    def assign(name: String, value: Int, commentStr: String = ""): Unit = {
      val cmt = if (commentStr.nonEmpty) s"  // $commentStr" else ""
      w.println(s"#ifndef $name")
      w.println(s"#define $name $value$cmt")
      w.println(s"#endif")
    }
    def assignHex(name: String, value: Int, width: Int): Unit = {
      val hex = String.format(s"%0${width}X", Integer.valueOf(value))
      w.println(s"#ifndef $name")
      w.println(s"#define $name 0x$hex")
      w.println(s"#endif")
    }

    def emitInstrR(name: String, hexOp: String): Unit = {
      val m = s"BORG_INSTR_${name.toUpperCase}(rd, rs1, rs2, funct3)"
      w.println(f"#define $m%-40s (0x${hexOp}UL | $C_ARGS_R)")
    }
    def emitInstrR4(name: String, hexOp: String): Unit = {
      val m = s"BORG_INSTR_${name.toUpperCase}(rd, rs1, rs2, rs3, funct3)"
      w.println(f"#define $m%-40s (0x${hexOp}UL | $C_ARGS_R4)")
    }
    def emitInstrR1(name: String, hexOp: String): Unit = {
      val m = s"BORG_INSTR_${name.toUpperCase}(rd, rs1, funct3)"
      w.println(f"#define $m%-40s (0x${hexOp}UL | $C_ARGS_FNEG)")
    }
    def emitInstr0(name: String, hexOp: String): Unit = {
      val m = s"BORG_INSTR_${name.toUpperCase}"
      w.println(f"#define $m%-35s 0x${hexOp}UL")
    }

    def defReg(name: String, addr: String): Unit = {
      w.println(f"#define $name%-17s (*(volatile uint32_t *)($addr))")
    }

    def defRegArray(name: String, addr: String): Unit = {
      val macroName = s"$name(n)"
      w.println(f"#define $macroName%-17s (*(volatile uint32_t *)($addr + (n) * 4))")
    }

    def defMacro(name: String, args: String, expr: String): Unit = {
      val macroName = if (args.isEmpty) name else s"$name($args)"
      w.println(f"#define $macroName%-25s $expr")
    }
  }

  class PythonEmitter(w: java.io.PrintWriter) extends Emitter(w) {
    def comment(text: String): Unit = w.println(s"# $text")
    def assign(name: String, value: Int, commentStr: String = ""): Unit = {
      val cmt = if (commentStr.nonEmpty) s"  # $commentStr" else ""
      w.println(s"$name = $value$cmt")
    }
    def assignHex(name: String, value: Int, width: Int): Unit = {
      val hex = String.format(s"%0${width}X", Integer.valueOf(value))
      w.println(s"$name = 0x$hex")
    }

    def emitInstrR(name: String, hexOp: String): Unit = {
      w.println(s"def encode_rv32_$name(rs1=0, rs2=1, rd=2, funct3=0):")
      w.println(s"    return (0x$hexOp | $PY_ARGS_R)")
    }
    def emitInstrR4(name: String, hexOp: String): Unit = {
      w.println(s"def encode_rv32_$name(rs1=0, rs2=1, rs3=3, rd=2, funct3=0):")
      w.println(s"    return (0x$hexOp | $PY_ARGS_R4)")
    }
    def emitInstrR1(name: String, hexOp: String): Unit = {
      w.println(s"def encode_rv32_$name(rs1=0, rd=1, funct3=0):")
      w.println(s"    return (0x$hexOp | $PY_ARGS_FNEG)")
    }
    def emitInstr0(name: String, hexOp: String): Unit = {
      // Not typically needed in python host, but added for completeness
      w.println(s"def encode_rv32_$name(): return 0x$hexOp")
    }
  }

  private def emitAllConstants(e: Emitter): Unit = {
    val methods = MmioMap.getClass.getMethods
    for (m <- methods.sortBy(_.getName)) {
      val name = m.getName
      // Only emit ALL_CAPS methods that take no parameters and return Int
      if (name == name.toUpperCase && m.getParameterCount == 0 && m.getReturnType == Integer.TYPE) {
        val value = m.invoke(MmioMap).asInstanceOf[Int]
        if (name.endsWith("_BASE") || name == "USER_BASE" || name == "DONE_MARKER") {
          e.assignHex(name, value, 8)
        } else {
          e.assign(name, value)
        }
      }
    }
  }

  private def emitCommon(e: Emitter): Unit = {
    e.section("MmioMap Constants (Auto-Extracted)")
    emitAllConstants(e)

    e.section("Borg instruction encoding (32-bit RISC-V R-type / R4-type)")
    def hex(i: BigInt) = f"$i%08X"
    e.emitInstrR("fadd", hex(ADD(0,0,0)))
    e.emitInstrR("fmul", hex(MUL(0,0,0)))
    e.emitInstrR4("fmadd", hex(FMA(0,0,0,0)))
    e.emitInstrR1("fneg", hex(FNEG(0,0)))
    e.emitInstrR1("fstep", hex(FSTEP(0,0)))
    e.emitInstrR1("frcp", hex(FRCP(0,0)))
    e.emitInstr0("halt", "00000000")
  }

  /** Emit C header with all MMIO addresses. */
  def emitHeader(path: String): Unit = {
    val w = new java.io.PrintWriter(path)
    val e = new CEmitter(w)
    e.comment("Auto-generated by MmioGenerator.scala — do not edit manually")

    emitCommon(e)

    e.section("C Macros for Address Casting")
    e.defReg("UART_TX",     "UART_BASE + UART_TX_OFFSET")
    e.defReg("UART_STATUS", "UART_BASE + UART_STATUS_OFFSET")
    e.defReg("UART_BAUD",   "UART_BASE + UART_BAUD_OFFSET")
    
    e.defRegArray("BORG_REG",  "BORG_BASE + BORG_REG_OFFSET")
    e.defRegArray("BORG_IMEM", "BORG_BASE + BORG_IMEM_OFFSET")
    e.defReg("BORG_CONTROL",   "BORG_BASE + BORG_CONTROL_OFFSET")
    e.defReg("BORG_STATUS",    "BORG_BASE + BORG_CONTROL_OFFSET")

    e.defMacro("BORG_CTL_PC", "pc", "(((pc) & BORG_CTL_PC_MASK) << BORG_CTL_PC_SHIFT)")

    e.defReg("BORG_ITER_BBOX", "BORG_BASE + BORG_ITER_BBOX_OFFSET")
    e.defReg("BORG_ITER",      "BORG_BASE + BORG_ITER_OFFSET")
    e.defReg("BORG_FRAG_PC",   "BORG_BASE + BORG_FRAG_PC_OFFSET")
    e.defMacro("BORG_ITER_PACK_BBOX", "x0,y0,x1,y1", "(((y1)<<BORG_ITER_BBOX_Y1_SHIFT)|((x1)<<BORG_ITER_BBOX_X1_SHIFT)|((y0)<<BORG_ITER_BBOX_Y0_SHIFT)|((x0)<<BORG_ITER_BBOX_X0_SHIFT))")
    e.defMacro("BORG_ITER_X", "v", "(((v) >> BORG_ITER_X_SHIFT) & BORG_ITER_COORD_MASK)")
    e.defMacro("BORG_ITER_Y", "v", "(((v) >> BORG_ITER_Y_SHIFT) & BORG_ITER_COORD_MASK)")
    e.defMacro("BORG_ITER_VALID", "v", "(((v) >> BORG_ITER_VALID_SHIFT) & 1)")
    e.defMacro("BORG_ITER_INSIDE", "v", "(((v) >> BORG_ITER_INSIDE_SHIFT) & 1)")

    e.defRegArray("BORG_UNIFORM", "BORG_BASE + BORG_UNIFORM_OFFSET")

    e.section("Tile buffer (Step 11.2)")
    e.defReg("BORG_TILE_CTRL", "BORG_BASE + BORG_TILE_CTRL_OFFSET")
    e.defReg("BORG_TILE_RG",   "BORG_BASE + BORG_TILE_RG_OFFSET")
    e.defReg("BORG_TILE_BZ",   "BORG_BASE + BORG_TILE_BZ_OFFSET")
    e.defMacro("BORG_TILE_SET_IDX", "idx", "do { BORG_TILE_CTRL = (idx) & 0xF; } while(0)")
    e.defMacro("BORG_TILE_CLEAR",   "", "do { BORG_TILE_CTRL = 0x10; } while(0)")
    e.defMacro("BORG_TILE_R", "rg", "(((rg) >> 16) & 0xFFFF)")
    e.defMacro("BORG_TILE_G", "rg", "((rg) & 0xFFFF)")
    e.defMacro("BORG_TILE_B", "bz", "(((bz) >> 16) & 0xFFFF)")
    e.defMacro("BORG_TILE_Z", "bz", "((bz) & 0xFFFF)")
    e.defRegArray("PSRAM_IN",  "PSRAM_BASE")
    e.defRegArray("PSRAM_OUT", "PSRAM_BASE + PSRAM_OUT_OFFSET")

    e.section("Convenience")
    w.println("#define STARTUP_DELAY() do { \\")
    w.println("    for (volatile int i = 0; i < STARTUP_DELAY_CYCLES; i++) ; \\")
    w.println("  } while (0)")

    w.close()
    println(s"Generated MMIO header: $path")
  }

  /** Emit Python constants file for host scripts. */
  def emitPython(path: String): Unit = {
    val w = new java.io.PrintWriter(path)
    val e = new PythonEmitter(w)
    e.comment("Auto-generated by MmioGenerator.scala — do not edit manually")

    emitCommon(e)

    e.section("PSRAM addresses (QSPI/SPI space)")
    w.println(f"PSRAM_IO_SPI_ADDR = 0x${PSRAM_SPI_BASE}%06X")
    e.assign("PSRAM_OUT_OFFSET", PSRAM_OUT_OFFSET)

    e.section("System clock additions")


    w.close()
    println(s"Generated Python constants: $path")
  }
}
