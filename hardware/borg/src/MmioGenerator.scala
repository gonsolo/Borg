// Copyright Andreas Wendleder 2026
// CERN-OHL-S-2.0

package borg

import chisel3._
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

    def emitBundle(structName: String, bundle: Bundle): Unit = {}
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

    override def emitBundle(structName: String, bundle: Bundle): Unit = {
      w.println()
      w.println("typedef union {")
      w.println("    struct {")

      var totalWidth = 0
      def walk(b: Data, prefix: String = ""): Unit = {
        b match {
          case bun: Bundle =>
            // Fix: Chisel 3 elements yield in reverse-declaration order. We must reverse them
            // so we emit the first declared element first. In C bitfields, the first element
            // starts at the LSB, which correctly maps to the lowest bits of the register.
            bun.elements.toSeq.reverse.foreach { case (name, data) =>
              val newPrefix = if (prefix.isEmpty) name else s"${prefix}_$name"
              walk(data, newPrefix)
            }
          case v: chisel3.Element =>
            val width = v.getWidth
            w.println(s"        uint32_t $prefix : $width;")
            totalWidth += width
        }
      }
      
      walk(bundle)
      
      val pad = 32 - totalWidth
      if (pad > 0) {
        w.println(s"        uint32_t _pad  : $pad;")
      }
      w.println("    };")
      w.println("    uint32_t raw;")
      w.println(s"} $structName;")
      w.println()
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

    override def emitBundle(structName: String, bundle: Bundle): Unit = {
      var totalWidth = 0
      val sn = structName.toUpperCase.replace("_T", "")
      def walk(b: Data, prefix: String = ""): Unit = {
        b match {
          case bun: Bundle =>
            bun.elements.toSeq.reverse.foreach { case (name, data) =>
              val newPrefix = if (prefix.isEmpty) name else s"${prefix}_$name"
              walk(data, newPrefix)
            }
          case v: chisel3.Element =>
            val width = v.getWidth
            val px = prefix.toUpperCase
            w.println(s"${sn}_${px}_SHIFT = $totalWidth")
            w.println(s"${sn}_${px}_MASK = ${(1 << width) - 1}")
            totalWidth += width
        }
      }
      walk(bundle)
      w.println()
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
    w.println("#include \"borg_gpu_regs.h\"")
    w.println("#define BORG_GPU ((volatile borg_gpu_t*) BORG_BASE)")
    
    e.defReg("UART_TX",     "UART_BASE + UART_TX_OFFSET")
    e.defReg("UART_STATUS", "UART_BASE + UART_STATUS_OFFSET")
    e.defReg("UART_BAUD",   "UART_BASE + UART_BAUD_OFFSET")
    
    // The legacy BORG_REG, BORG_IMEM, BORG_UNIFORM accessors have been migrated to borg_gpu_regs.h

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
