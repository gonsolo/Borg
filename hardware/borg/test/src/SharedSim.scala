// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// In chisel3.simulator for the package-private pieces of the stimulus API.
package chisel3.simulator

import chisel3.RawModule
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import svsim.{Simulation, Workspace}

/** Drop-in for `EphemeralSimulator`: a design is compiled once per JVM.
  *
  * `EphemeralSimulator.simulate` elaborates, runs Verilator and compiles for every call;
  * the Borg suites call it 240 times, mostly for the same few designs, and the Verilator
  * build is nearly all of their time. Here every call still elaborates (cheap, and the
  * only way to know which design the call means), but the emitted SystemVerilog is
  * hashed and the compiled simulator is reused when the hash has been seen. Each test
  * still starts the executable afresh, so it begins in reset-free power-on state exactly
  * as before.
  *
  * Switch a test file over by importing `chisel3.simulator.SharedSim._` in place of
  * `chisel3.simulator.EphemeralSimulator._`.
  */
class SharedSimulator(base: svsim.CommonCompilationSettings, resetFirst: Boolean) extends PeekPokeAPI {

  private val backend = svsim.verilator.Backend.initializeFromProcessEnvironment()
  private val tag = "verilator"
  private val workdir = "workdir"

  private val root: Path = {
    val p = Files.createTempDirectory("shared-sim")
    sys.addShutdownHook(deleteTree(p))
    p
  }
  private var counter = 0
  private val compiled = mutable.Map[String, (Workspace, Simulation)]()

  /** (hits, builds) so far, for the log. */
  def counts: (Int, Int) = synchronized((hits, compiled.size))
  private var hits = 0

  private def deleteTree(p: Path): Unit =
    if (Files.exists(p)) {
      val s = Files.walk(p)
      try s.sorted(java.util.Comparator.reverseOrder[Path]()).iterator().asScala.foreach(Files.delete)
      finally s.close()
    }

  private def digestOf(dir: String, extra: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(extra.getBytes("UTF-8"))
    val base = Paths.get(dir)
    val s = Files.walk(base)
    val files =
      try s.iterator().asScala.filter(Files.isRegularFile(_)).toSeq.sortBy(base.relativize(_).toString)
      finally s.close()
    files.foreach { f =>
      md.update(base.relativize(f).toString.getBytes("UTF-8"))
      md.update(Files.readAllBytes(f))
    }
    md.digest().map("%02x".format(_)).mkString
  }

  private def assertionFailures(w: Workspace): Option[Throwable] = {
    val log = Paths.get(w.absolutePath, s"$workdir-$tag", "simulation-log.txt").toFile
    val lines = scala.io.Source.fromFile(log).getLines().filter(backend.assertionFailed.matches(_)).toSeq
    Option.when(lines.nonEmpty)(
      new Exception(s"Assertion failed in ${log}:\n${lines.mkString("\n")}")
    )
  }

  def simulate[T <: RawModule](
    module:       => T,
    layerControl: LayerControl.Type = LayerControl.EnableAll,
    additionalResetCycles: Int = 0
  )(body: (T) => Unit): Unit = synchronized {
    counter += 1
    val dir = Files.createDirectories(root.resolve(s"w$counter"))
    val workspace = new Workspace(path = dir.toString, workingDirectoryPrefix = workdir)
    workspace.reset()
    // `SimulatorAPI.simulate`'s flavour (what the -O1 suites used): reset gates asserts and printfs, and
    // the reset procedure runs before the test body. The cast is safe, T only reaches the lambdas.
    val settings =
      (if (resetFirst) Settings.default[chisel3.Module].asInstanceOf[Settings[T]] else Settings.defaultRaw[T])
        .copy(verilogLayers = layerControl)
    val elaborated = workspace.elaborateGeneratedModule(() => module)
    val key = digestOf(workspace.primarySourcesPath, layerControl.toString)

    val (owner, simulation) = compiled.get(key) match {
      case Some(hit) =>
        hits += 1
        deleteTree(dir)
        hit
      case None =>
        val primaryDirs = {
          val base = Paths.get(workspace.primarySourcesPath)
          val s = Files.walk(base)
          try s.iterator().asScala.filter(Files.isDirectory(_)).map(_.toString).toSeq
          finally s.close()
        }
        val common = base
        val updated = common.copy(
          includeDirs = Some(common.includeDirs.getOrElse(Seq.empty) ++ primaryDirs),
          verilogPreprocessorDefines =
            common.verilogPreprocessorDefines ++ settings.preprocessorDefines(elaborated),
          fileFilter = common.fileFilter.orElse(settings.verilogLayers.shouldIncludeFile(elaborated)),
          directoryFilter = common.directoryFilter.orElse(
            settings.verilogLayers.shouldIncludeDirectory(elaborated, workspace.primarySourcesPath)
          ),
          linkLibraryPaths = common.linkLibraryPaths ++ settings.libraryPaths,
          simulationSettings = common.simulationSettings.copy(
            plusArgs = common.simulationSettings.plusArgs ++ settings.plusArgs,
            enableWavesAtTimeZero = common.simulationSettings.enableWavesAtTimeZero || settings.enableWavesAtTimeZero
          )
        )
        workspace.generateAdditionalSources(timescale = updated.defaultTimescale)
        val sim = workspace.compile(backend)(
          tag,
          updated,
          svsim.verilator.Backend.CompilationSettings.default,
          None,
          false
        )
        val entry = (workspace, sim)
        compiled(key) = entry
        entry
    }

    val outcome = scala.util.Try {
      simulation.runElaboratedModule(elaborated, traceEnabled = false) { (m: SimulatedModule[T]) =>
        if (resetFirst) m.wrapped match {
          case dut: chisel3.Module =>
            stimulus.ResetProcedure.module[chisel3.Module](additionalResetCycles)(dut)
          case _ =>
        }
        body(m.wrapped)
        m.completeSimulation()
      }
    }
    assertionFailures(owner) match {
      case Some(e) => throw e
      case None    => outcome.get
    }
  }
}

/** The default Verilator build (-O3). */
object SharedSim extends SharedSimulator(svsim.CommonCompilationSettings(), resetFirst = false)

/** For full-`Borg` and `BorgTestWrapper` designs: Verilator's -O1 build (OptimizeForCompilationSpeed)
  * and `SimulatorAPI.simulate`'s reset procedure. At this scale the default -O3 costs ~900 s per
  * build, most of it Verilator's own translation pass, and correctness tests that run a few hundred
  * cycles gain nothing from -O3's faster steady state.
  */
object SharedSimFast
    extends SharedSimulator(
      svsim.CommonCompilationSettings.default.copy(
        optimizationStyle = svsim.CommonCompilationSettings.OptimizationStyle.OptimizeForCompilationSpeed
      ),
      resetFirst = true
    )
