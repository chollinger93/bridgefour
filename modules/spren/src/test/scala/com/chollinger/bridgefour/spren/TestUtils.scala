package com.chollinger.bridgefour.spren

import scala.concurrent.duration.DurationDouble
import scala.language.postfixOps

import cats.effect.IO
import com.chollinger.bridgefour.shared.jobs._
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
object TestUtils {

  val sprenCfg: SprenConfig = SprenConfig(0, "http", "0.0.0.0", 5555, 2, 0.2.seconds)

  val jobId                    = 100
  val taskId                   = 200
  val workerId                 = 0
  val slotId                   = 0
  val taskIdTuple: TaskIdTuple = TaskIdTuple(taskId, jobId)
  val slotIdTuple: SlotIdTuple = SlotIdTuple(slotId, workerId)

  val sampleTask: AssignedTaskConfig = AssignedTaskConfig(
    taskId = taskIdTuple,
    slotId = slotIdTuple,
    input = "sample",
    output = "out",
    jobClass = Jobs.sampleJobClass,
    userSettings = Map("taskId" -> taskId.toString)
  )

  def delayedTask(timeout: Int): AssignedTaskConfig = AssignedTaskConfig(
    taskId = taskIdTuple,
    slotId = slotIdTuple,
    input = "sample",
    output = "out",
    jobClass = Jobs.alwaysOkJobClass,
    userSettings = Map("timeout" -> timeout.toString)
  )

  object Jobs {

    val sampleJobClass   = "com.chollinger.bridgefour.shared.jobs.SampleBridgeFourJob"
    val alwaysOkJobClass = "com.chollinger.bridgefour.spren.AlwaysOkBridgeFourJob"

    case class AlwaysOkBridgeFourJob(cfg: AssignedTaskConfig) extends BridgeFourJob[IO] {

      def run(): IO[BackgroundTaskState] = IO.println("Starting") >>
        IO.pure(
          BackgroundTaskState(id = cfg.userSettings("taskId").toInt, status = ExecutionStatus.Done)
        ) <* IO.println("Done")

    }

  }

}
