package com.chollinger.bridgefour.kaladin.services

import cats.implicits.*
import cats.Monad
import cats.Parallel
import cats.effect.kernel.Async
import cats.effect.Async
import cats.effect.Concurrent
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.shared.extensions.EventuallyConsistent
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.Persistence
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.http4s.client.Client
import org.typelevel.log4cats.Logger

sealed trait WorkerCache[F[_]] {

  def get(id: WorkerId): F[Option[WorkerConfig]]

  def add(cfg: WorkerConfig): F[Unit]

  def list(): F[Map[WorkerId, WorkerConfig]]

}

object WorkerCache {

  def makeF[F[_]: Async](
      cfg: ServiceConfig,
      workers: Persistence[F, WorkerId, WorkerConfig]
  ): F[WorkerCache[F]] = {
    cfg.workers
      .foldLeft(Async[F].unit) { case (_, w) =>
        workers.put(w.id, w)
      }
      .map(_ =>
        new WorkerCache[F] {
          override def get(id: WorkerId): F[Option[WorkerConfig]] =
            workers.get(id)

          override def add(cfg: WorkerConfig): F[Unit] = workers.put(cfg.id, cfg)

          override def list(): F[Map[WorkerId, WorkerConfig]] = workers.list()
        }
      )
  }

}
