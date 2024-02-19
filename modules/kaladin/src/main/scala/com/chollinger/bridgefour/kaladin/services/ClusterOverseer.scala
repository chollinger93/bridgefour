package com.chollinger.bridgefour.kaladin.services

import cats.Parallel
import cats.effect.kernel.Async
import cats.effect.Concurrent
import cats.effect.Sync
import cats.implicits.*
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.shared.exceptions.Exceptions.MisconfiguredClusterException
import com.chollinger.bridgefour.shared.extensions.StronglyConsistent
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.Worker.*
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.client.Client
import org.typelevel.log4cats.Logger

sealed trait ClusterOverseer[F[_]] {

  protected def checkWorkerState(workerCfg: WorkerConfig): F[WorkerState]

  def getWorkerState(cfg: WorkerConfig): F[WorkerState]

  @StronglyConsistent
  def getClusterState(): F[ClusterState]

}

object ClusterOverseer {

  def make[F[_]: Async: Parallel: Concurrent: ThrowableMonadError: Logger](
      cfg: ServiceConfig,
      client: Client[F]
  ): ClusterOverseer[F] =
    new ClusterOverseer[F] {

      val sF: Sync[F]                     = implicitly[Sync[F]]
      val err: ThrowableMonadError[F]     = implicitly[ThrowableMonadError[F]]
      given EntityDecoder[F, WorkerState] = accumulatingJsonOf[F, WorkerState]

      def getWorkerState(cfg: WorkerConfig): F[WorkerState] = client.expect[WorkerState](s"${cfg.uri()}/worker/state")

      // Mismatched workers are a catastrophic failure
      private def catchMismatchedWorkers(workerCfg: WorkerConfig, res: WorkerState): F[Unit] = {
        if (res.id != workerCfg.id) {
          Logger[F].error(
            s"Expected ID ${workerCfg.id} from ${workerCfg.uri()} but got ${res.id}"
          ) >>
            err.raiseError(
              MisconfiguredClusterException(
                s"Worker ID mismatch: Expected ${workerCfg.id} from ${workerCfg.uri()} but got ${res.id}! " +
                  s"Check the worker's configuration and ensure that the worker is properly configured."
              )
            )
        } else {
          sF.unit
        }
      }

      override protected def checkWorkerState(workerCfg: WorkerConfig): F[WorkerState] = {
        err.handleErrorWith(client.get(s"${workerCfg.uri()}/worker/state") { r =>
          for {
            res <- r.as[WorkerState]
            _   <- Logger[F].debug(s"Worker id ${workerCfg.id}(${res.id}) @ ${workerCfg.uri()} status: ${res.status}")
            _   <- catchMismatchedWorkers(workerCfg, res)
          } yield res
        })(t =>
          if (t.isInstanceOf[MisconfiguredClusterException]) {
            err.raiseError(t)
          } else {
            Logger[F].warn(
              s"No response from worker id ${workerCfg.id} @ ${workerCfg.uri()}: $t"
            ) >> sF.blocking(
              WorkerState.unavailable(workerCfg.id)
            )
          }
        )
      }

      override def getClusterState(): F[ClusterState] =
        for {
          _ <- Logger[F].debug(s"Configured workers: ${cfg.workers}")
          state <- cfg.workers
                     .parTraverse(c => checkWorkerState(c))
          _      <- Logger[F].debug(s"Worker responses: $state")
          cluster = ClusterState(cfg.workers, state)
          _      <- Logger[F].debug(s"Cluster: $cluster")
        } yield cluster

    }

}
