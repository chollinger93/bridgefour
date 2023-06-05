package com.chollinger.bridgefour.kaladin.services

import cats.effect.kernel.Async
import cats.effect.IO
import cats.effect.Sync
import cats.implicits.*
import cats.syntax.parallel.*
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.WorkerStatus
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.http4s.Status
import org.http4s.circe.JsonDecoder
import org.http4s.client.Client

sealed trait HealthMonitorService[F[_]] {

  def checkWorkerStatus(workerCfg: WorkerConfig): F[WorkerStatus]

  def checkClusterStatus(): F[Map[WorkerId, WorkerStatus]]

}

object HealthMonitorService {

  def make[F[_]: Sync: Parallel: ThrowableMonadError](cfg: ServiceConfig, client: Client[F]): HealthMonitorService[F] =
    new HealthMonitorService[F] {

      val sF: Sync[F]                 = implicitly[Sync[F]]
      val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]

      override def checkWorkerStatus(workerCfg: WorkerConfig): F[WorkerStatus] = {
        err.handleError(client.get(s"${workerCfg.uri()}/worker/status") { r =>
          sF.blocking(r.status match {
            case Status.Ok => WorkerStatus.Alive
            case _         => WorkerStatus.Dead
          })
        })(_ => WorkerStatus.Dead)
      }

      override def checkClusterStatus(): F[Map[WorkerId, WorkerStatus]] =
        cfg.workers.parTraverse(c => checkWorkerStatus(c).map(r => (c.id, r))).map(e => e.toMap)

    }

}
