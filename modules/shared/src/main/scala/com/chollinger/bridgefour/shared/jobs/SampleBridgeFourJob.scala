package com.chollinger.bridgefour.shared.jobs

import scala.language.postfixOps

import cats.effect.kernel.Async
import cats.implicits._
import com.chollinger.bridgefour.shared.models.Job._
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig

// Does nothing but return "Done"
case class SampleBridgeFourJob[F[_]](cfg: AssignedTaskConfig) extends BridgeFourJob[F] {

  given Async[F] = summon[Async[F]]
  override def run(): F[BackgroundTaskState] =
    Async[F].blocking(BackgroundTaskState(id = cfg.taskId.id, status = ExecutionStatus.Done))

}
