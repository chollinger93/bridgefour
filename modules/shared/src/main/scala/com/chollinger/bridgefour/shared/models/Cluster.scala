package com.chollinger.bridgefour.shared.models

import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.SlotId
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import io.circe.Decoder
import io.circe.Encoder
import org.latestbit.circe.adt.codec.JsonTaggedAdt
import com.chollinger.bridgefour.shared.models.Worker.WorkerState

enum ClusterStatus derives JsonTaggedAdt.Codec {

  case Healthy
  case Degraded
  case Dead

}

object Cluster {

  // TODO: make shared model
  case class SlotCountOverview(
      open: Int,
      processing: Int,
      total: Int
  )

  object SlotCountOverview {

    def apply(states: List[WorkerState]): SlotCountOverview = SlotCountOverview(
      open = states.map(_.availableSlots).map(_.size).sum,
      processing = states.map(_.slots).map(s => s.filter(s => ExecutionStatus.isRunning(s.status))).map(_.size).sum,
      total = states.map(_.slots.size).sum
    )

  }

  // The state and health of the overall cluster
  case class ClusterState(
      status: ClusterStatus,
      workers: Map[WorkerId, WorkerState],
      slots: SlotCountOverview
  ) derives Encoder.AsObject,
        Decoder

  object ClusterState {

    def apply(workerCfgs: List[WorkerConfig], states: List[WorkerState]): ClusterState = {
      val expectedWorkerIds = workerCfgs.map(_.id)
      val onlineWorkerIds   = states.filter(_.status == WorkerStatus.Alive).map(_.id)
      val clusterStatus =
        if (expectedWorkerIds.size == onlineWorkerIds.size) ClusterStatus.Healthy else ClusterStatus.Degraded
      val workers      = states.map(w => (w.id, w)).toMap
      val slotOverview = SlotCountOverview(states)

      ClusterState(
        status = clusterStatus,
        workers = workers,
        slots = slotOverview
      )
    }

  }

}
