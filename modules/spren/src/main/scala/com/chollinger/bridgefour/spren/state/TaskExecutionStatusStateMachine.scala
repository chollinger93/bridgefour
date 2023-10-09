package com.chollinger.bridgefour.spren.state

import com.chollinger.bridgefour.shared.background.BackgroundWorker.BackgroundWorkerResult
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.SlotTaskIdTuple
import com.chollinger.bridgefour.shared.models.Job.JobDetails
import com.chollinger.bridgefour.shared.models.Job.TaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus.finished
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Status.WorkerTaskStatus
import com.chollinger.bridgefour.shared.models.Worker.SlotState
import com.chollinger.bridgefour.shared.state.StateMachine

class TaskExecutionStatusStateMachine
    extends StateMachine[SlotState, BackgroundWorkerResult[_, TaskState, SlotTaskIdTuple]] {

  /** This tries to model the behavior of the TaskExecutor, which is not a true State Machine.
    *
    * Note that the actual state for a Task is different - a task can never be missing.
    *
    * However, that is irrelevant, since the leader will never pass missing TaskIDs
    *
    * @param initialSlot
    *   Usually an empty state, because event can contain a default Slot State as part of its metadata
    * @param event
    *   Change in background worker
    * @return
    *   Current slot state
    */
  def transition(initialSlot: SlotState, event: BackgroundWorkerResult[_, TaskState, SlotTaskIdTuple]): SlotState =
    event.res match
      case Right(r) => SlotState(initialSlot.id, available = true, status = r.status, Some(r.id))
      case Left(s) =>
        event.meta.match {
          // BackgroundWorker takes some responsibility for state transitions - bad coupling
          case Some(m) =>
            s match {
              case ExecutionStatus.InProgress => SlotState(m.slot, available = false, status = s, Some(m.task))
              // This does not re-start these tasks anywhere, that is done in the leader
              case ExecutionStatus.Done | ExecutionStatus.Error | ExecutionStatus.Halted =>
                SlotState(m.slot, available = true, status = s, Some(m.task))
              case ExecutionStatus.Missing => SlotState.empty(initialSlot.id)
            }
          case _ => SlotState.empty(initialSlot.id)
        }

}
