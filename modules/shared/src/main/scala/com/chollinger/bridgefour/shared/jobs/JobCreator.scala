package com.chollinger.bridgefour.shared.jobs

import cats.effect.kernel.Async
import com.chollinger.bridgefour.shared.models.Job.JobDetails
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig

import scala.language.postfixOps

trait BridgeFourJobCreator[F[_]] {

  def makeJob(className: String, cfg: AssignedTaskConfig): BridgeFourJob[F] = {
    val cls      = Class.forName(className)
    val ctor     = cls.getDeclaredConstructor(classOf[AssignedTaskConfig])
    val instance = ctor.newInstance(cfg)
    instance.asInstanceOf[BridgeFourJob[F]]
  }

}

trait LeaderCreator[F[_]]() {

  def makeJob(className: String, job: JobDetails): LeaderJob[F] = {
    val cls      = Class.forName(className)
    val ctor     = cls.getDeclaredConstructor(classOf[JobDetails])
    val instance = ctor.newInstance(job)
    instance.asInstanceOf[LeaderJob[F]]
  }

}

object LeaderCreatorService {

  def make[F[_]: Async](): LeaderCreator[F] = new LeaderCreator[F] {}

}

object BridgeFourJobCreatorService {

  def make[F[_]: Async](): BridgeFourJobCreator[F] = new BridgeFourJobCreator[F] {}

}
