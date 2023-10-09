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
import org.typelevel.log4cats.Logger

sealed trait HealthMonitorService[F[_]] {

  def checkWorkerStatus(workerCfg: WorkerConfig): F[WorkerStatus]

  def checkClusterStatus(): F[Map[WorkerId, WorkerStatus]]

}

object HealthMonitorService {

  def make[F[_]: Sync: Parallel: ThrowableMonadError: Logger](
      cfg: ServiceConfig,
      client: Client[F]
  ): HealthMonitorService[F] =
    new HealthMonitorService[F] {

      val sF: Sync[F]                 = implicitly[Sync[F]]
      val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]

      override def checkWorkerStatus(workerCfg: WorkerConfig): F[WorkerStatus] = {
        err.handleErrorWith(client.get(s"${workerCfg.uri()}/worker/status") { r =>
          Logger[F].debug(s"Worker ${workerCfg.uri()} status: ${r.status}") >>
            sF.blocking(r.status match {
              case Status.Ok => WorkerStatus.Alive
              case _         => WorkerStatus.Dead
            })
        })(t =>
          Logger[F].warn(s"No response from worker at ${workerCfg.uri()}: ${t.printStackTrace()}") >> sF.blocking(
            WorkerStatus.Dead
          )
        )
      }

      override def checkClusterStatus(): F[Map[WorkerId, WorkerStatus]] =
        Logger[F].debug(s"Workers: ${cfg.workers}") >> cfg.workers
          .parTraverse(c => checkWorkerStatus(c).map(r => (c.id, r)))
          .map(e => e.toMap)

    }

}
