package com.chollinger.bridgefour.kaladin.services

import cats.effect.kernel.Async
import cats.effect.Concurrent
import cats.effect.IO
import cats.effect.Sync
import cats.implicits.*
import cats.syntax.parallel.*
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.UserJobConfig
import com.chollinger.bridgefour.shared.models.Worker.*
import com.chollinger.bridgefour.shared.models.ClusterStatus
import com.chollinger.bridgefour.shared.models.WorkerStatus
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.http4s.EntityDecoder
import org.http4s.EntityEncoder
import org.http4s.Status
import org.http4s.circe.JsonDecoder
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import io.circe.disjunctionCodecs.encodeEither
import org.http4s.circe.accumulatingJsonOf

sealed trait HealthMonitorService[F[_]] {

  protected def checkWorkerState(workerCfg: WorkerConfig): F[WorkerState]

  def checkClusterStatus(): F[ClusterState]

}

object HealthMonitorService {

  def make[F[_]: Async: Parallel: Concurrent: ThrowableMonadError: Logger](
      cfg: ServiceConfig,
      client: Client[F]
  ): HealthMonitorService[F] =
    new HealthMonitorService[F] {

      val sF: Sync[F]                     = implicitly[Sync[F]]
      val err: ThrowableMonadError[F]     = implicitly[ThrowableMonadError[F]]
      given EntityDecoder[F, WorkerState] = accumulatingJsonOf[F, WorkerState]

      override protected def checkWorkerState(workerCfg: WorkerConfig): F[WorkerState] = {
        err.handleErrorWith(client.get(s"${workerCfg.uri()}/worker/state") { r =>
          for {
            _   <- Logger[F].debug(s"Worker ${workerCfg.uri()} status: ${r.status}")
            res <- r.as[WorkerState]
          } yield res
        })(t =>
          Logger[F].warn(s"No response from worker at ${workerCfg.uri()}: ${t.printStackTrace()}") >> sF.blocking(
            WorkerState.unavailable(workerCfg.id)
          )
        )
      }

      override def checkClusterStatus(): F[ClusterState] =
        for {
          _ <- Logger[F].debug(s"Workers: ${cfg.workers}")
          state <- cfg.workers
                     .parTraverse(c => checkWorkerState(c))
          cluster = ClusterState(cfg.workers, state)
          _      <- Logger[F].debug(s"Cluster: $cluster")
        } yield cluster

    }

}
