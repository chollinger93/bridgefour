package com.chollinger.bridgefour.spren.state

import cats.effect.IO
import com.chollinger.bridgefour.spren.TestUtils.slotIdTuple
import com.chollinger.bridgefour.spren.TestUtils.taskIdTuple
import com.chollinger.bridgefour.shared.background.BackgroundWorker.BackgroundWorkerResult
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.SlotTaskIdTuple
import com.chollinger.bridgefour.shared.models.Job.TaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.SlotState
import munit.CatsEffectSuite

class TaskExecutionStatusStateMachineSuite extends CatsEffectSuite {

  private val sm = TaskExecutionStatusStateMachine()
  // Started is what TaskExecutor provides to BackgroundWorker as meta
  private val meta = SlotTaskIdTuple(slotIdTuple, taskIdTuple)
  private val init = SlotState.empty(slotIdTuple)
  private val exp  = SlotState.started(slotIdTuple, taskIdTuple)

  test("TaskExecutionStatusStateMachine transitions in progress") {
    // In Progress
    val e1 = BackgroundWorkerResult[IO, TaskState, SlotTaskIdTuple](Left(ExecutionStatus.InProgress), Some(meta))
    val s1 = sm.transition(init, e1)
    assertEquals(s1, exp)
  }
  test("TaskExecutionStatusStateMachine transitions error") {
    // Error
    val e1 = BackgroundWorkerResult[IO, TaskState, SlotTaskIdTuple](Left(ExecutionStatus.Error), Some(meta))
    val s1 = sm.transition(init, e1)
    assertEquals(s1, SlotState(slotIdTuple, available = true, status = ExecutionStatus.Error, Some(meta.task)))
  }
  test("TaskExecutionStatusStateMachine transitions success") {
    // Success
    val e1 = BackgroundWorkerResult[IO, TaskState, SlotTaskIdTuple](
      Right(TaskState(taskIdTuple, ExecutionStatus.Done)),
      Some(meta)
    )
    val s1 = sm.transition(init, e1)
    assertEquals(s1, SlotState(slotIdTuple, available = true, status = ExecutionStatus.Done, Some(meta.task)))
  }
  test("TaskExecutionStatusStateMachine transitions fiber failure") {
    // Success
    val e1 = BackgroundWorkerResult[IO, TaskState, SlotTaskIdTuple](
      Right(TaskState(taskIdTuple, ExecutionStatus.Error)),
      Some(meta)
    )
    val s1 = sm.transition(init, e1)
    assertEquals(s1, SlotState(slotIdTuple, available = true, status = ExecutionStatus.Error, Some(meta.task)))
  }

}
