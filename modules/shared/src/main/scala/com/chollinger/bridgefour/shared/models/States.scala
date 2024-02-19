package com.chollinger.bridgefour.shared.models

import com.chollinger.bridgefour.shared.models.IDs.JobId
import com.chollinger.bridgefour.shared.models.IDs.SlotId
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskId
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import io.circe.Decoder
import io.circe.Encoder
import org.latestbit.circe.adt.codec.JsonTaggedAdt

object States {

  // A SlotState is reported by a worker. They are unaware of what exactly they are working on. The state is ephemeral.
  // The leader keeps track of it persistently.
  case class SlotState private (
      id: SlotId,
      status: ExecutionStatus
  ) derives Encoder.AsObject,
        Decoder {

    def available(): Boolean = ExecutionStatus.available(status)

  }

  object SlotState {

    def apply(id: SlotId, status: ExecutionStatus): SlotState =
      new SlotState(id, status = status)

    def started(id: SlotId, taskId: TaskIdTuple): SlotState =
      SlotState(id, ExecutionStatus.InProgress)

    def empty(id: SlotId): SlotState = SlotState(id, ExecutionStatus.Missing)

  }

  // A TaskState is a database object that only exists for the leader to keep track of the state of a task/
  case class TaskState(id: TaskId, jobId: JobId, workerId: WorkerId, status: ExecutionStatus)
      derives Encoder.AsObject,
        Decoder

  // A JobState is a database object that only exists for the leader to keep track of the state of a whole job.
  case class JobState(id: JobId, tasks: List[TaskId], status: ExecutionStatus)

}
