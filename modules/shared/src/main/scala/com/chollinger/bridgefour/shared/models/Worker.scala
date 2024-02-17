package com.chollinger.bridgefour.shared.models

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

enum WorkerStatus derives JsonTaggedAdt.Codec {

  case Alive
  case Dead

}

object Worker {

  case class WorkerState(
      id: WorkerId,
      slots: List[SlotState],
      allSlots: List[SlotId],
      availableSlots: List[SlotId],
      status: WorkerStatus = WorkerStatus.Alive
      // See https://github.com/circe/circe/pull/2009
  ) derives Encoder.AsObject,
        Decoder

  object WorkerState {

    def apply(id: WorkerId, slots: List[SlotState]): WorkerState = {
      val allSlotIds    = slots.map(_.id)
      val unusedSlotIds = slots.filter(_.available).map(_.id)
      val runningTasks  = slots.filter(_.status == ExecutionStatus.InProgress)
      val status        = if (slots.isEmpty) WorkerStatus.Dead else WorkerStatus.Alive
      new WorkerState(id, slots, allSlotIds, unusedSlotIds, status)
    }

    def unavailable(id: WorkerId): WorkerState = WorkerState(id, List.empty, List.empty, List.empty, WorkerStatus.Dead)

  }

}
