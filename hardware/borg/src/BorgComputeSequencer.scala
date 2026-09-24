// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Dispatch parameters, latched at `start` (the MMIO registers may change
  * while a dispatch runs). */
class ComputeDispatchIO extends Bundle {
  val start      = Input(Bool())
  val pc         = Input(UInt(6.W))
  val groupsX    = Input(UInt(16.W))
  val groupsY    = Input(UInt(16.W))
  val groupsZ    = Input(UInt(16.W))
  val localX     = Input(UInt(8.W))
  val localY     = Input(UInt(8.W))
  // localX * localY * localZ, supplied by the driver: localZ itself is never
  // needed, because z is simply what x and y carry into.
  val localCount = Input(UInt(8.W))
}

/** What the lanes see in compute mode. */
/** Sequencer -> BorgCore: integer IDs for r30/r31 in place of the pixel
  * centre, per lane, and the lanes a trigger starts with. Compute's invocation
  * IDs, or a vertex shader's VertexIndex/InstanceIndex. */
class InvocationIdsIO(val cfg: BorgConfig) extends Bundle {
  val mode     = Bool()
  val laneMask = UInt(cfg.fragLanes.W)
  val r30      = Vec(cfg.fragLanes, UInt(cfg.totalBits.W))  // LocalInvocationIndex
  val r31      = Vec(cfg.fragLanes, UInt(cfg.totalBits.W))  // LocalInvocationID, x | y << 10 | z << 20
}

/** BorgCore -> BorgComputeSequencer: why the invocation that just stopped did
  * so. See Instructions.FUNCT7_BARRIER. */
class ComputeBarrierIO extends Bundle {
  val hit      = Bool()      // stopped at BARRIER, not HALT
  val resumePC = UInt(CoreTriggerIO.PcBits.W)  // valid only when hit; may lie past IMEM
}

class BorgComputeSequencerIO(val cfg: BorgConfig) extends Bundle {
  val mmio         = new ComputeDispatchIO
  val busy         = Output(Bool())
  val done         = Output(Bool())
  val coreTrigger  = new CoreTriggerIO
  val coreStatus   = Flipped(new CoreStatusIO)
  val barrier      = Input(new ComputeBarrierIO)
  val lanes        = Output(new InvocationIdsIO(cfg))
  val uniformWrite = new MemWritePort(6, cfg.totalBits)
  // Sticky: set when quads of one workgroup disagreed about whether/where
  // they hit a BARRIER. Cleared by the next dispatch's start, same shape as
  // BorgCore's branch_divergent/exec_fault.
  val barrierFault = Output(Bool())
}

/** BorgComputeSequencer -- vkCmdDispatch on the shader core.
  *
  * Compute reuses the core unchanged: a workgroup runs as a sequence of quads
  * of `fragLanes` invocations, one quad at a time, each started through the
  * same `coreTrigger` the graphics sequencer uses. What differs is only what
  * the shader can see:
  *
  *   - r30/r31 read as raw integers (the lane's LocalInvocationIndex and its
  *     packed LocalInvocationID) instead of pixel centres;
  *   - uniforms u29/u30/u31 of page 0 hold WorkgroupID x/y/z, written here
  *     before each workgroup -- they are uniform across it, so one shared
  *     write serves every lane of every quad;
  *   - the last quad of a workgroup whose size is not a multiple of
  *     `fragLanes` starts with the missing lanes masked off, so they execute
  *     nothing observable (no register writes, no memory access).
  *
  * Everything a shader computes from these -- GlobalInvocationID, addresses,
  * NumWorkGroups (a push constant) -- is ordinary integer code.
  *
  * One workgroup at a time, one quad at a time, and every memory access stalls
  * the core until it completes: memory is sequentially consistent across all
  * invocations of a dispatch by construction.
  *
  * == BARRIER: a segment loop over the same quad walk ==
  *
  * OpControlBarrier needs every invocation of a workgroup to reach it before
  * any of them proceeds past it. Since a workgroup here is a SEQUENCE of
  * quads (not concurrent lanes), that becomes: run every quad of the
  * workgroup up to its BARRIER, THEN run every quad again from just past it,
  * as a second pass over the exact same (lin, lx, ly, lz) walk sLane already
  * does -- a "segment" is just which PC that walk starts each quad at.
  * `segStartPC` is that PC; it resets to the dispatch entry point `pc` at the
  * start of each workgroup and advances to the barrier's resume point each
  * time every quad agrees they hit one.
  *
  * "Agrees" is enforced, not assumed: SPIR-V requires uniform control flow
  * through a control barrier (every invocation executes the same one), and a
  * shader that violates it would otherwise make some quads finish (HALT)
  * while others are still waiting at a BARRIER that can now never be
  * reached by every quad -- a real hang, not just a wrong image, if nothing
  * else read the mismatch. So instead, of the up-to-fragLanes*maxQuads
  * invocations in one segment: if any two disagree about whether they
  * stopped at a BARRIER at all, or (both barrier) disagree about the resume
  * PC, `barrierFault` sets sticky and the workgroup is treated as finished --
  * same "observable, not silently wrong, not hung" shape as BorgCore's
  * branch_divergent/exec_fault.
  */
class BorgComputeSequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgComputeSequencerIO(cfg))
  private val N = cfg.fragLanes

  private val sIdle :: sWgIds :: sLane :: sTrigger :: sWait :: sDone :: Nil = Enum(6)
  private val state = RegInit(sIdle)

  private val pc      = RegInit(0.U(6.W))  // dispatch entry point (segment 0 of every workgroup)
  private val groupsX = RegInit(0.U(16.W))
  private val groupsY = RegInit(0.U(16.W))
  private val groupsZ = RegInit(0.U(16.W))
  private val sizeX   = RegInit(0.U(8.W))
  private val sizeY   = RegInit(0.U(8.W))
  private val count   = RegInit(0.U(8.W))

  private val wgX = RegInit(0.U(16.W))
  private val wgY = RegInit(0.U(16.W))
  private val wgZ = RegInit(0.U(16.W))

  // --- Current segment (see class doc) ---
  private val segStartPC    = RegInit(0.U(CoreTriggerIO.PcBits.W))
  private val segSawBarrier = RegInit(false.B)  // some quad this segment stopped at BARRIER
  private val segSawHalt    = RegInit(false.B)  // some quad this segment stopped at HALT
  private val segResumePC   = RegInit(0.U(CoreTriggerIO.PcBits.W)) // the (checked-common) resume PC, once segSawBarrier
  private val barrierFault  = RegInit(false.B)

  // The next invocation to hand out, as a linear index and as (x, y, z).
  private val lin = RegInit(0.U(8.W))
  private val lx  = RegInit(0.U(8.W))
  private val ly  = RegInit(0.U(8.W))
  private val lz  = RegInit(0.U(8.W))

  private val lane    = RegInit(0.U(log2Up(N).W))
  // Same 0-width index at N == 1 as BorgCore's memLaneIdx, for the same reason.
  private val laneSel: UInt = if (N == 1) 0.U(0.W) else lane
  private val uniIdx  = RegInit(0.U(2.W))
  private val laneIdx = RegInit(VecInit(Seq.fill(N)(0.U(8.W))))
  private val laneId  = RegInit(VecInit(Seq.fill(N)(0.U(30.W))))
  private val laneOn  = RegInit(VecInit(Seq.fill(N)(false.B)))

  private val coreWasActive = RegNext(io.coreStatus.running || io.coreStatus.autoRunPending, false.B)
  private val coreFinished  = coreWasActive && !io.coreStatus.running && !io.coreStatus.autoRunPending

  io.busy := state =/= sIdle
  io.done := state === sDone
  io.barrierFault := barrierFault

  io.coreTrigger.valid  := state === sTrigger
  io.coreTrigger.pc     := segStartPC
  io.coreTrigger.isRast := false.B
  io.coreTrigger.isSetup := false.B

  io.lanes.mode     := state =/= sIdle
  io.lanes.laneMask := laneOn.asUInt
  for (i <- 0 until N) {
    io.lanes.r30(i) := laneIdx(i)
    io.lanes.r31(i) := laneId(i)
  }

  io.uniformWrite.en   := state === sWgIds
  io.uniformWrite.addr := Cat(0.U(1.W), 29.U(5.W) + uniIdx)
  io.uniformWrite.data := MuxLookup(uniIdx, wgZ)(Seq(0.U -> wgX, 1.U -> wgY))

  /** Reset the per-quad walk (lin/lx/ly/lz/lane, via sWgIds -> sLane) and
    * (re)write the workgroup-ID uniforms -- shared by "next workgroup" and
    * "next segment of this same workgroup" (the ID hasn't changed either
    * way, so re-writing it there is harmless, not just cheap: one fewer
    * special case). Caller sets segStartPC first. */
  private def startWorkgroup(): Unit = {
    uniIdx := 0.U
    state  := sWgIds
  }

  /** Begin a fresh segment of the CURRENT workgroup, starting every quad at
    * `startPC`. */
  private def beginSegment(startPC: UInt): Unit = {
    segStartPC    := startPC
    segSawBarrier := false.B
    segSawHalt    := false.B
    startWorkgroup()
  }

  switch(state) {
    is(sIdle) {
      when(io.mmio.start) {
        pc      := io.mmio.pc
        groupsX := io.mmio.groupsX
        groupsY := io.mmio.groupsY
        groupsZ := io.mmio.groupsZ
        sizeX   := io.mmio.localX
        sizeY   := io.mmio.localY
        count   := io.mmio.localCount
        wgX := 0.U; wgY := 0.U; wgZ := 0.U
        barrierFault := false.B
        val empty = io.mmio.groupsX === 0.U || io.mmio.groupsY === 0.U || io.mmio.groupsZ === 0.U ||
                    io.mmio.localX === 0.U || io.mmio.localY === 0.U || io.mmio.localCount === 0.U
        when(empty) { state := sDone }.otherwise { beginSegment(io.mmio.pc) }
      }
    }

    is(sWgIds) {
      uniIdx := uniIdx + 1.U
      when(uniIdx === 2.U) {
        lin := 0.U; lx := 0.U; ly := 0.U; lz := 0.U
        lane := 0.U
        state := sLane
      }
    }

    // One lane per cycle: stepping (x, y, z) one invocation at a time needs
    // only one wrap-incrementer, where doing all lanes at once needs a chain.
    is(sLane) {
      val valid = lin < count
      laneIdx(laneSel) := lin
      laneId(laneSel)  := Cat(lz, 0.U(2.W), ly, 0.U(2.W), lx)
      laneOn(laneSel)  := valid
      when(valid) {
        lin := lin + 1.U
        when(lx + 1.U === sizeX) {
          lx := 0.U
          when(ly + 1.U === sizeY) {
            ly := 0.U
            lz := lz + 1.U
          }.otherwise { ly := ly + 1.U }
        }.otherwise { lx := lx + 1.U }
      }
      lane := lane + 1.U
      when(lane === (N - 1).U) { state := sTrigger }
    }

    is(sTrigger) { state := sWait }

    is(sWait) {
      when(coreFinished) {
        // Fold this quad's stop reason into the segment's aggregate. Reads of
        // segSawBarrier/segResumePC below see their value from BEFORE this
        // quad (Chisel Reg semantics), i.e. "what prior quads this segment
        // already established" -- exactly what a same-segment disagreement
        // check needs. sawBarrierNext/sawHaltNext additionally fold in THIS
        // quad, for the "is the segment, as of the quad that just finished,
        // clean" decision below (which needs its own contribution, not just
        // the ones before it -- otherwise a one-quad-workgroup could never
        // see its own barrier hit).
        val hitBarrier    = io.barrier.hit
        val sawBarrierNext = segSawBarrier || hitBarrier
        val sawHaltNext    = segSawHalt    || !hitBarrier
        when(sawBarrierNext && sawHaltNext) { barrierFault := true.B }
        when(hitBarrier) {
          when(segSawBarrier && io.barrier.resumePC =/= segResumePC) {
            barrierFault := true.B
          }.elsewhen(!segSawBarrier) {
            segResumePC := io.barrier.resumePC
          }
        }
        segSawBarrier := sawBarrierNext
        segSawHalt    := sawHaltNext

        when(lin < count) {
          lane  := 0.U
          state := sLane
        }.elsewhen(sawBarrierNext && !sawHaltNext) {
          // Every quad of this workgroup hit the same barrier: next segment.
          beginSegment(Mux(segSawBarrier, segResumePC, io.barrier.resumePC))
        }.elsewhen(wgX +& 1.U < groupsX) {
          wgX := wgX + 1.U
          beginSegment(pc)
        }.elsewhen(wgY +& 1.U < groupsY) {
          wgX := 0.U
          wgY := wgY + 1.U
          beginSegment(pc)
        }.elsewhen(wgZ +& 1.U < groupsZ) {
          wgX := 0.U
          wgY := 0.U
          wgZ := wgZ + 1.U
          beginSegment(pc)
        }.otherwise {
          state := sDone
        }
      }
    }

    is(sDone) { state := sIdle }
  }
}
