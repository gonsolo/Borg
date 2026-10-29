// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Dispatch parameters, latched at `start`: the MMIO registers may change mid-dispatch. */
class ComputeDispatchIO extends Bundle {
  val start      = Input(Bool())
  val pc         = Input(UInt(6.W))
  val groupsX    = Input(UInt(16.W))
  val groupsY    = Input(UInt(16.W))
  val groupsZ    = Input(UInt(16.W))
  val localX     = Input(UInt(8.W))
  val localY     = Input(UInt(8.W))
  // localX * localY * localZ: z is just what x and y carry into.
  val localCount = Input(UInt(8.W))
}

/** Sequencer -> BorgCore: per-lane integer IDs for r30/r31 in place of the pixel centre
  * (invocation IDs, or VertexIndex/InstanceIndex), and the lanes a trigger starts with. */
class InvocationIdsIO(val cfg: BorgConfig) extends Bundle {
  val mode     = Bool()
  val laneMask = UInt(cfg.fragLanes.W)
  val r30      = Vec(cfg.fragLanes, UInt(cfg.totalBits.W))  // LocalInvocationIndex
  val r31      = Vec(cfg.fragLanes, UInt(cfg.totalBits.W))  // LocalInvocationID, x | y << 10 | z << 20
}

/** BorgCore -> BorgComputeSequencer: why the invocation that just stopped did so. */
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
  // Sticky until the next start: quads of one workgroup disagreed about a BARRIER.
  val barrierFault = Output(Bool())
}

/** vkCmdDispatch on the shader core: a workgroup runs as a sequence of quads through the
  * graphics `coreTrigger`. r30/r31 hold LocalInvocationIndex/ID, u29-u31 of page 0 hold
  * WorkgroupID, and a partial last quad masks its missing lanes. One quad at a time, and every
  * memory access stalls the core, so memory is sequentially consistent by construction.
  *
  * BARRIER: run every quad up to the barrier, then every quad again from its resume PC
  * (a new segment). Quads that disagree about the barrier set `barrierFault` and end the
  * workgroup instead of hanging.
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

  private val segStartPC    = RegInit(0.U(CoreTriggerIO.PcBits.W))
  private val segSawBarrier = RegInit(false.B)  // some quad this segment stopped at BARRIER
  private val segSawHalt    = RegInit(false.B)  // some quad this segment stopped at HALT
  private val segResumePC   = RegInit(0.U(CoreTriggerIO.PcBits.W)) // valid once segSawBarrier
  private val barrierFault  = RegInit(false.B)

  // The next invocation to hand out, as a linear index and as (x, y, z).
  private val lin = RegInit(0.U(8.W))
  private val lx  = RegInit(0.U(8.W))
  private val ly  = RegInit(0.U(8.W))
  private val lz  = RegInit(0.U(8.W))

  private val lane    = RegInit(0.U(log2Up(N).W))
  private val laneSel: UInt = lane
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

  // Restart the quad walk and rewrite the workgroup IDs (harmless for a new segment).
  private def startWorkgroup(): Unit = {
    uniIdx := 0.U
    state  := sWgIds
  }

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

    // One lane per cycle needs one wrap-incrementer instead of a chain.
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
        // segSaw* hold the prior quads; the *Next values include this one.
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
