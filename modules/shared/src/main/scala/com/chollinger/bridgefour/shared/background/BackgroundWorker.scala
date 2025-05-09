package com.chollinger.bridgefour.shared.background

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.effect.implicits._
import cats.effect.kernel.Async
import cats.effect.kernel.Fiber
import cats.effect.kernel.Outcome
import cats.implicits._
import com.chollinger.bridgefour.shared.background.BackgroundWorker._
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.persistence.Persistence
import org.typelevel.log4cats.Logger

/** A generic background worker that can store effectful background tasks with metadata
  *
  * Metadata can be retrieved before the fiber completes
  *
  * This is a low level interface. A recommended implementation exists in TaskExecutorService
  *
  * @tparam F
  *   Effect
  * @tparam A
  *   Fiber return type
  * @tparam M
  *   Metadata type
  */
trait BackgroundWorker[F[_], A, M] {

  def start(key: Long, f: F[A], meta: Option[M] = None): F[ExecutionStatus]

  def get(key: Long): F[Option[FiberContainer[F, A, M]]]

  def getResult(key: Long): F[BackgroundWorkerResult[F, A, M]]

  def probeResult(key: Long, timeout: FiniteDuration): F[BackgroundWorkerResult[F, A, M]]

  // TODO: stop

}

object BackgroundWorker {

  case class FiberContainer[F[_], A, M](fib: Fiber[F, Throwable, A], meta: Option[M])

  // TODO: arguably, this could be a Tuple instead of an Either
  case class BackgroundWorkerResult[F[_], A, M](res: Either[ExecutionStatus, A], meta: Option[M])

}

object BackgroundWorkerService {

  // TODO: Consider make[F[_]: Async, A](): Resource[F, BackgroundWorker[F, A]]
  def make[F[_]: Async: Logger, A, M](state: Persistence[F, Long, FiberContainer[F, A, M]]): BackgroundWorker[F, A, M] =
    new BackgroundWorker[F, A, M]:

      val async: Async[F] = implicitly[Async[F]]

      override def start(key: Long, f: F[A], meta: Option[M] = None): F[ExecutionStatus] =
        for {
          fib <- async.start(f)
          data = FiberContainer(fib, meta)
          _   <- state.put(key, data)
          r   <- state.get(key)
          _   <- Logger[F].debug(s"Started fiber: $r at $key")
          _   <- async.raiseWhen(r.isEmpty)(new Exception("Failed to start fiber"))
        } yield ExecutionStatus.InProgress

      override def get(key: Long): F[Option[FiberContainer[F, A, M]]] = state.get(key)

      private def parseMeta(data: Option[FiberContainer[F, A, M]]): Option[M] = data match
        case Some(c) => c.meta
        case _       => None

      private def parseFiber(data: Option[FiberContainer[F, A, M]]): F[BackgroundWorkerResult[F, A, M]] =
        data match
          case Some(d) =>
            d.fib.join.flatMap {
              case Outcome.Succeeded(value) => value.map(r => BackgroundWorkerResult(Right(r), d.meta))
              case _                        => async.pure(BackgroundWorkerResult(Left(ExecutionStatus.Error), d.meta))
            }
          case None => async.pure(BackgroundWorkerResult(Left(ExecutionStatus.Missing), None))

      override def probeResult(key: Long, timeout: FiniteDuration): F[BackgroundWorkerResult[F, A, M]] = for {
        fib <- state.get(key)
        res <- parseFiber(fib).timeoutTo(
                 timeout,
                 async.blocking(BackgroundWorkerResult(Left(ExecutionStatus.InProgress), parseMeta(fib)))
               )
      } yield res

      override def getResult(key: Long): F[BackgroundWorkerResult[F, A, M]] = for {
        fib <- state.get(key)
        res <- parseFiber(fib)
      } yield res

}
