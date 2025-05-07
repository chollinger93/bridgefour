package com.chollinger.bridgefour.spren

import scala.concurrent.duration.DurationDouble
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import cats.effect.IO
import cats.effect.Sync
import cats.effect.Temporal
import cats.effect.kernel.Async
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger

import java.util.concurrent.TimeUnit
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
    jobClass = Jobs.delayedJobClass,
    userSettings = Map("timeout" -> timeout.toString)
  )

  object Jobs {

    val sampleJobClass   = "com.chollinger.bridgefour.shared.jobs.SampleBridgeFourJob"
    val alwaysOkJobClass = "com.chollinger.bridgefour.spren.AlwaysOkBridgeFourJob"
    val delayedJobClass  = "com.chollinger.bridgefour.spren.DelayedBridgeFourJob"

  }

}

object AlwaysOkBridgeFourJobJobCreatorService {

  def make[F[_]: Async](): BridgeFourJobCreator[F] = new BridgeFourJobCreator[F] {
    override def makeJob(className: String, cfg: AssignedTaskConfig): BridgeFourJob[F] =
      AlwaysOkBridgeFourJob(cfg).asInstanceOf[BridgeFourJob[F]]
  }

}

case class AlwaysOkBridgeFourJob(cfg: AssignedTaskConfig) extends BridgeFourJob[IO] {

  def run(): IO[BackgroundTaskState] = IO.println("Starting") >>
    IO.pure(
      BackgroundTaskState(id = cfg.taskId.id, status = ExecutionStatus.Done)
    ) <* IO.println("Done")

}

case class DelayedBridgeFourJob(cfg: AssignedTaskConfig) extends BridgeFourJob[IO] {

  given unsafeLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def run(): IO[BackgroundTaskState] =
    for {
      _        <- Logger[IO].info(s"Starting delayed job")
      waitTimeS = cfg.userSettings.getOrElse("timeout", "30").toInt
      _        <- Temporal[IO].sleep(FiniteDuration(waitTimeS, TimeUnit.SECONDS))
      _        <- Logger[IO].info(s"Done with job after $waitTimeS")
      r        <- Async[IO].delay(BackgroundTaskState(id = cfg.taskId.id, status = ExecutionStatus.Done))
    } yield r

}
