package com.chollinger.bridgefour.shared.models

import com.chollinger.bridgefour.shared.models.IDs.SlotId
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.TaskState
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
      runningTasks: List[TaskIdTuple]
      // See https://github.com/circe/circe/pull/2009
  ) derives Encoder.AsObject,
        Decoder

  object WorkerState {

    def unavailable(id: WorkerId): WorkerState = WorkerState(id, List.empty, List.empty, List.empty, List.empty)

  }

  case class SlotState(
      id: SlotIdTuple,
      available: Boolean,
      status: ExecutionStatus,
      taskId: Option[TaskIdTuple]
  ) derives Encoder.AsObject,
        Decoder

  object SlotState {

    def started(id: SlotIdTuple, taskId: TaskIdTuple): SlotState =
      SlotState(id, false, ExecutionStatus.InProgress, Some(taskId))
    def empty(id: SlotIdTuple): SlotState = SlotState(id, true, ExecutionStatus.Missing, None)

  }

}
