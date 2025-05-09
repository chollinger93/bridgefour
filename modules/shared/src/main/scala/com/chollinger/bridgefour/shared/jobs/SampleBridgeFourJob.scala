package com.chollinger.bridgefour.shared.jobs

import scala.language.postfixOps

import cats.effect.IO
import cats.implicits._
import com.chollinger.bridgefour.shared.models.Job._
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig

// Does nothing but return "Done"
case class SampleBridgeFourJob(cfg: AssignedTaskConfig) extends BridgeFourJob[IO] {

  override def run(): IO[BackgroundTaskState] =
    IO.blocking(BackgroundTaskState(id = cfg.taskId.id, status = ExecutionStatus.Done))

}
