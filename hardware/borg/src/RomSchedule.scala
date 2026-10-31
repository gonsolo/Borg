// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

/** List scheduling of straight-line ROM code for the B9 pipeline: a result is ready
  * `Latency` issue slots after its instruction (with the bypass), a unit op (uniform read,
  * SOUT) stalls its slot until it is done. Dependences through registers (also reuse) and
  * the order of side effects are kept, so the ROM computes exactly the same values. */
private[borg] object RomSchedule {
  val Latency = 5

  private case class Op(word: BigInt, writes: Option[Int], reads: Seq[Int], effect: Boolean, unit: Boolean)

  private def decode(w: BigInt): Op = {
    val i = w.toLong
    def f(lo: Int) = ((i >> lo) & 31).toInt
    val (rd, rs1, rs2, rs3) = (f(7), f(15), f(20), f(27))
    def wr(r: Int) = Option.when(r != 0)(r)
    (i & 0x7F).toInt match {
      case 0x33 | 0x53     => Op(w, wr(rd), Seq(rs1, rs2), effect = false, unit = false)
      case 0x13 | 0x0B     => Op(w, wr(rd), Seq(rs1), effect = false, unit = false)
      case 0x37            => Op(w, wr(rd), Nil, effect = false, unit = false)
      case 0x43 | 0x47 | 0x4B | 0x4F => Op(w, wr(rd), Seq(rs1, rs2, rs3), effect = false, unit = false)
      case 0x73 if ((i >> 12) & 7) == 2 && rs1 == 0 && {
            val csr = ((i >> 20) & 0xFFF).toInt; csr >= Csr.Uniform0 && csr < Csr.Uniform0 + 32 } =>
        Op(w, wr(rd), Nil, effect = false, unit = true)
      case 0x5B            => Op(w, None, Seq(rs2), effect = true, unit = true)
      case _               => Op(w, wr(rd), Seq(rs1, rs2, rs3), effect = true, unit = true)
    }
  }

  def apply(code: Seq[BigInt]): Seq[BigInt] = {
    val ops = code.map(decode)
    val n = ops.size
    // preds(j): (i, the issue slots j must wait after i).
    val preds = Array.fill(n)(scala.collection.mutable.ArrayBuffer[(Int, Int)]())
    val lastWrite = scala.collection.mutable.Map[Int, Int]()
    val readsSince = scala.collection.mutable.Map[Int, List[Int]]().withDefaultValue(Nil)
    var lastEffect = -1
    for ((o, j) <- ops.zipWithIndex) {
      for (r <- o.reads.filter(_ != 0).distinct; i <- lastWrite.get(r))
        preds(j) += i -> (if (ops(i).unit) 1 else Latency)
      for (r <- o.writes) {
        lastWrite.get(r).foreach(i => preds(j) += i -> 1)
        readsSince(r).foreach(i => if (i != j) preds(j) += i -> 1)
      }
      if (o.effect && lastEffect >= 0) preds(j) += lastEffect -> 1
      for (r <- o.reads.filter(_ != 0)) readsSince(r) = j :: readsSince(r)
      for (r <- o.writes) { lastWrite(r) = j; readsSince(r) = Nil }
      if (o.effect) lastEffect = j
    }
    val succs = Array.fill(n)(scala.collection.mutable.ArrayBuffer[(Int, Int)]())
    for (j <- 0 until n; (i, d) <- preds(j)) succs(i) += j -> d
    val height = Array.fill(n)(0)
    for (i <- (0 until n).reverse) height(i) = (succs(i).map { case (j, d) => d + height(j) } :+ 1).max
    val time = Array.fill(n)(-1)
    val out = scala.collection.mutable.ArrayBuffer[BigInt]()
    var t = 0
    while (out.size < n) {
      val ready = (0 until n).filter(j => time(j) < 0 && preds(j).forall { case (i, _) => time(i) >= 0 })
      def at(j: Int) = (preds(j).map { case (i, d) => time(i) + d } :+ t).max
      val j = ready.minBy(j => (at(j), -height(j), j))
      time(j) = at(j)
      t = time(j) + 1
      out += ops(j).word
    }
    out.toSeq
  }
}
