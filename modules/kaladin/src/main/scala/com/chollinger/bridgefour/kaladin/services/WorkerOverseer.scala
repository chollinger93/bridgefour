package com.chollinger.bridgefour.kaladin.services

import java.io.File

import cats.effect.Concurrent
import cats.effect.implicits.parallelForGenSpawn
import cats.effect.instances.all.parallelForGenSpawn
import cats.effect.instances.spawn.parallelForGenSpawn
import cats.effect.kernel.Async
import cats.effect.kernel.implicits.parallelForGenSpawn
import cats.effect.kernel.instances.all.parallelForGenSpawn
import cats.effect.kernel.instances.spawn.parallelForGenSpawn
import cats.implicits.*
import cats.Monad
import cats.effect
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.shared.exceptions.Exceptions.NoFilesAvailableException
import com.chollinger.bridgefour.shared.exceptions.Exceptions.NoWorkersAvailableException
import com.chollinger.bridgefour.shared.extensions.takeN
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.client.Client
import org.typelevel.log4cats.Logger

trait WorkerOverseer[F[_]] {

  def getWorkerState(cfg: WorkerConfig): F[WorkerState]

  def listAvailableWorkers(): F[List[WorkerState]]

}

object WorkerOverseerService {

  // TODO: mutex
  def make[F[_]: ThrowableMonadError: Concurrent: Logger](
      cfg: ServiceConfig,
      client: Client[F]
      //      availableSlots: Counter[F, WorkerId],
  ): WorkerOverseer[F] =
    new WorkerOverseer[F] {

      val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]

      given EntityDecoder[F, WorkerState] = accumulatingJsonOf[F, WorkerState]

      def getWorkerState(cfg: WorkerConfig): F[WorkerState] = client.expect[WorkerState](s"${cfg.uri()}/worker/state")

      override def listAvailableWorkers(): F[List[WorkerState]] = {
        for {
          avail <-
            cfg.workers.parTraverse(w => err.handleError(getWorkerState(w))(t => WorkerState.unavailable(w.id)))
          _ <- Logger[F].debug(s"Available worker slots: ${avail.map(_.availableSlots)}")
          //          _     <- avail.parTraverse(w => availableSlots.set(w.id, w.availableSlots)) // TODO: not needed
        } yield avail
      }

    }

}
