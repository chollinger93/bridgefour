package com.chollinger.bridgefour.kaladin.services

import cats.effect.Async
import cats.implicits._
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.persistence.Persistence
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger

sealed trait WorkerCache[F[_]] {

  def get(id: WorkerId): F[Option[WorkerConfig]]

  def add(cfg: WorkerConfig): F[Unit]

  def remove(id: WorkerId): F[Boolean]

  def list(): F[Map[WorkerId, WorkerConfig]]

}

object WorkerCache {

  def makeF[F[_]: Async: Logger](
      cfg: ServiceConfig,
      workers: Persistence[F, WorkerId, WorkerConfig]
  ): F[WorkerCache[F]] = {
    given logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
    cfg.workers.map { w =>
      for {
        _ <- workers.put(w.id, w)
        s <- workers.size
        _ <- logger.info(s"Seeded cache with $s entries")
      } yield ()
    }.sequence.map(_ =>
      new WorkerCache[F] {
        override def get(id: WorkerId): F[Option[WorkerConfig]] =
          workers.get(id)

        override def add(cfg: WorkerConfig): F[Unit] = workers.put(cfg.id, cfg)

        override def list(): F[Map[WorkerId, WorkerConfig]] = workers.list()

        override def remove(id: WorkerId): F[Boolean] = workers
          .del(id)
          .map(v =>
            v match {
              case Some(_) => true
              case _       => false
            }
          )
      }
    )
  }

}
