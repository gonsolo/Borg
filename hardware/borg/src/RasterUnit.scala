// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** RAST's arithmetic: the raster ROM's draw program, operation for operation, each a new value. */
private[borg] object RasterProgram {
  sealed trait Src
  case class V(i: Int, neg: Boolean = false) extends Src
  case class U(k: Int, neg: Boolean = false) extends Src
  case object PX extends Src
  case object PY extends Src
  case object One extends Src
  case object Zero extends Src
  case class Op(a: Src, b: Src, c: Src, rcp: Boolean = false)

  private val R = BorgSetupRom.Record
  val recordWords: Int = R.SampleDepth + 2

  /** The operations and the registers they leave: (register, value). The sample depths are last. */
  val (ops, outs, sampleOuts): (Seq[Op], Seq[(Int, Int)], Seq[(Int, Int)]) = {
    val p = scala.collection.mutable.ArrayBuffer[Op]()
    def op(o: Op): Int = { p += o; p.length - 1 }
    def mul(a: Src, b: Src) = op(Op(a, b, Zero))
    def add(b: Src, c: Src) = op(Op(One, b, c))
    val e = (0 until 4).map { k =>
      val t = add(V(mul(PY, U(R.plane(k) + 1))), U(R.plane(k) + 2))
      op(Op(PX, U(R.plane(k)), V(t)))
    }
    val s = add(V(add(V(e(0)), V(e(1)))), V(e(2)))
    val r = op(Op(V(s), Zero, Zero, rcp = true))
    val n = op(Op(V(s, neg = true), V(r), U(R.One)))
    val inv = op(Op(V(r), V(n), V(r)))
    val bary = (0 until 3).map(k => mul(V(e(k)), V(inv)))
    val w = mul(V(s), U(R.InvDet))
    val z = add(V(mul(V(e(3)), U(R.DepthScale))), U(R.DepthOffset))
    val zf = add(U(R.One), V(e(3), neg = true))
    val sd = Seq(add(V(z), U(R.SampleDepth)), add(V(z), U(R.SampleDepth + 1)),
      add(V(z), U(R.SampleDepth + 1, neg = true)), add(V(z), U(R.SampleDepth, neg = true)))
    (p.toSeq, Seq(1, 2, 3, 4).zip(e) ++ Seq(5, 6, 7).zip(bary) ++ Seq(8 -> w, 10 -> zf, 29 -> z),
      Seq(11, 12, 13, 14).zip(sd))
  }

  /** One operation a cycle. An FMA's result is at its output 4 cycles after issue; a reciprocal's
    * a cycle after, read from the value registers from the next. */
  val fmaLatency = 4
  def store(i: Int): Int = issue(i) + (if (ops(i).rcp) 1 else fmaLatency)
  private def usable(j: Int, c: Int, at: Array[Int]): Boolean =
    at(j) >= 0 && c >= at(j) + (if (ops(j).rcp) 2 else fmaLatency)
  private def srcs(o: Op): Seq[Int] = Seq(o.a, o.b, o.c).collect { case V(i, _) => i }
  lazy val issue: Seq[Int] = {
    val at = Array.fill(ops.length)(-1)
    var c = 0
    while (at.contains(-1)) {
      ops.indices.find(i => at(i) < 0 && srcs(ops(i)).forall(usable(_, c, at))).foreach(at(_) = c)
      c += 1
    }
    at.toSeq
  }
  lazy val end: Int = ops.indices.map(store).max + 1
}

class RasterUnitIO(cfg: BorgConfig) extends CoreUnitIO(cfg) {
  val single      = Input(Bool())             // one sample: no per-sample depths
  val page        = Input(UInt(1.W))          // the slot's uniform page
  val pageWritten = Input(Valid(UInt(1.W)))   // a uniform write: the cached record of that page goes
  val unifEn      = Output(Bool())
  val unifIdx     = Output(UInt(5.W))
  val unifData    = Input(UInt(cfg.totalBits.W))   // a cycle after unifEn
}

/** RAST rs1, rs2: from the pixel centre in rs1 and rs2, the draw front end's per-pixel values
  * (docs/B1_geometry_front_end.md, "Raster ROM and fragment ABI") into r1-r8, r10, r29 and, with
  * more than one sample, r11-r14. An FMA per lane runs [[RasterProgram]], the raster ROM's
  * operations in its order, so the bits are the ROM's. The triangle's record stays while its page
  * is not written. */
class RasterUnit(cfg: BorgConfig) extends Module {
  val io = IO(new RasterUnitIO(cfg))
  import RasterProgram._
  private val N = cfg.fragLanes
  private val W = cfg.totalBits
  io.idle()
  io.unifEn := false.B; io.unifIdx := 0.U

  private val isRast = RegInit(false.B)
  when(io.issue.valid) { isRast := RvInt(io.issue.bits.word).rast }

  private val sIdle :: sLoad :: sRun :: Nil = Enum(3)
  private val st = RegInit(sIdle)
  private val start = io.atOps && isRast && st === sIdle
  private val px = RegEnable(io.a, start); private val py = RegEnable(io.b, start)
  private val mask = RegEnable(io.mask, start); private val single = RegEnable(io.single, start)

  // The record: loaded once per triangle.
  private val rec = Reg(Vec(recordWords, UInt(W.W)))
  private val recValid = RegInit(false.B); private val recPage = RegInit(0.U(1.W))
  private val dirty = RegInit(false.B)
  private val li = RegInit(0.U(log2Up(recordWords + 1).W))
  private val k = RegInit(0.U(log2Up(end + 1).W))
  when(io.pageWritten.valid && io.pageWritten.bits === recPage) { recValid := false.B; dirty := true.B }
  private val hit = recValid && recPage === io.page && !(io.pageWritten.valid && io.pageWritten.bits === io.page)
  when(start) {
    recPage := io.page; li := 0.U; k := 0.U; dirty := false.B
    st := Mux(hit, sRun, sLoad)
    when(recPage =/= io.page) { recValid := false.B }
  }
  when(st === sLoad) {
    io.unifEn := li < recordWords.U; io.unifIdx := li
    when(li =/= 0.U) { rec(li - 1.U) := io.unifData }
    li := li + 1.U
    when(li === recordWords.U) {
      st := sRun
      recValid := !dirty && !(io.pageWritten.valid && io.pageWritten.bits === recPage)
    }
  }
  private val running = st === sRun
  when(running && k =/= end.U) { k := k + 1.U }

  private val one = (((1 << (cfg.exp - 1)) - 1) << (cfg.sig - 1)).U(W.W)
  private def neg(x: UInt, n: Boolean): UInt = if (n) x ^ (1.U << (W - 1)) else x
  private val vals = Seq.fill(N)(Reg(Vec(ops.length, UInt(W.W))))
  private val special = Seq.fill(N)(Module(new SpecialFn(cfg)))
  private val fmas = Seq.fill(N)(Module(new BorgFma(cfg)))
  for (l <- 0 until N) {
    val fA = RegInit(0.U(W.W)); val fB = RegInit(0.U(W.W)); val fC = RegInit(0.U(W.W))
    val fma = fmas(l); val sp = special(l)
    fma.io.a := fA; fma.io.b := fB; fma.io.c := fC
    fma.io.negate := false.B; fma.io.pipeEn1 := true.B; fma.io.pipeEn2 := true.B
    def src(s: Src, c: Int): UInt = s match {
      case V(i, n) => neg(if (!ops(i).rcp && c == store(i)) fma.io.out else vals(l)(i), n)
      case U(i, n) => neg(rec(i), n)
      case PX => px(l)
      case PY => py(l)
      case One => one
      case Zero => 0.U(W.W)
    }
    val rcpIn = Reg(UInt(W.W))
    sp.io.lutIn := 0.U; sp.io.lutFrsq := false.B; sp.io.in := rcpIn
    sp.io.frcp := true.B; sp.io.frsq := false.B; sp.io.advance := true.B
    for ((o, i) <- ops.zipWithIndex) {
      when(running && k === issue(i).U) {
        if (o.rcp) { sp.io.lutIn := src(o.a, issue(i)); rcpIn := src(o.a, issue(i)) }
        else { fA := src(o.a, issue(i)); fB := src(o.b, issue(i)); fC := src(o.c, issue(i)) }
      }
      when(running && k === store(i).U) { vals(l)(i) := (if (o.rcp) sp.io.out else fma.io.out) }
    }
  }

  // Write-back: a register a cycle once its value is in, the sample depths last.
  private val order = outs.sortBy(o => store(o._2)) ++ sampleOuts.sortBy(o => store(o._2))
  private val wi = RegInit(0.U(log2Up(order.length + 1).W))
  private val lastWb = Mux(single, (outs.length - 1).U, (order.length - 1).U)
  private val ready = VecInit(order.map(o => k > store(o._2).U))(wi)
  io.wbWant := running && ready
  io.wb.zipWithIndex.foreach { case (w, l) =>
    w.en := running && ready && io.wbReady && mask(l)
    w.addr := VecInit(order.map(_._1.U(5.W)))(wi)
    w.data := VecInit(order.map(o => vals(l)(o._2)))(wi)
  }
  when(running && ready && io.wbReady) {
    wi := wi + 1.U
    when(wi === lastWb) {
      wi := 0.U; st := sIdle; isRast := false.B
      io.finish := true.B
    }
  }
}
