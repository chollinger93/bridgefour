package com.chollinger.bridgefour.spren

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.effect.IO
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.RockConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.TaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig

import concurrent.duration.DurationDouble
object TestUtils {

  val rockCfg = RockConfig(0, "http", "0.0.0.0", 5555, 2, 0.2 seconds)

  val jobId       = 100
  val taskId      = 200
  val workerId    = 0
  val slotId      = 0
  val taskIdTuple = TaskIdTuple(taskId, jobId)
  val slotIdTuple = SlotIdTuple(slotId, workerId)

  val sampleTask: AssignedTaskConfig = AssignedTaskConfig(
    taskId = taskIdTuple,
    slotId = slotIdTuple,
    input = "sample",
    output = "out",
    jobClass = JobClass.SampleJob,
    userSettings = Map()
  )

  def delayedTask(timeout: Int): AssignedTaskConfig = AssignedTaskConfig(
    taskId = taskIdTuple,
    slotId = slotIdTuple,
    input = "sample",
    output = "out",
    jobClass = JobClass.DelayedWordCountJob,
    userSettings = Map("timeout" -> timeout.toString)
  )

  object Jobs {

    case class AlwaysOkBridgeFourJob(config: AssignedTaskConfig) extends BridgeFourJob[IO] {

      val jobClass: JobClass = JobClass.AlwaysOkJob

      def run(): IO[TaskState] = IO.println("Starting") >>
        IO.pure(TaskState(id = config.taskId, status = ExecutionStatus.Done)) <* IO.println("Done")

    }

    case class FakeJobCreator() extends JobCreator[IO] {

      def makeJob(cfg: AssignedTaskConfig) = AlwaysOkBridgeFourJob(cfg)

    }

  }

}
