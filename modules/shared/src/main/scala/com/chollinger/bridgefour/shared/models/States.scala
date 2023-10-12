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

object States {

  case class SlotState(
      id: SlotIdTuple,
      available: Boolean,
      status: ExecutionStatus
  ) derives Encoder.AsObject,
        Decoder

  object SlotState {

    def started(id: SlotIdTuple, taskId: TaskIdTuple): SlotState =
      SlotState(id, false, ExecutionStatus.InProgress)

    def empty(id: SlotIdTuple): SlotState = SlotState(id, true, ExecutionStatus.Missing)

  }

}
