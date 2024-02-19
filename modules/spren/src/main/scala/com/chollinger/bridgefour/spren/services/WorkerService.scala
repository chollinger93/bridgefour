package com.chollinger.bridgefour.spren.services

import cats.Parallel
import cats.effect.Sync
import cats.implicits.*
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.spren.programs.TaskExecutor
import org.typelevel.log4cats.Logger

import scala.language.postfixOps

/** A generic overseer that reports on the overall status of the worker, i.e. about open/used slots & running tasks
  *
  * Likely to be a program that utilizes a TaskExecutor[F], but other implementations that are stateful are possible
  *
  * It is recommendation to use a higher-level interface in the implementation
  *
  * @tparam F
  *   Effect
  */
sealed trait WorkerService[F[_]] {

  def state(): F[WorkerState]

}

object WorkerService {

  def make[F[_]: Parallel: Sync: Logger](
      cfg: SprenConfig,
      executor: TaskExecutor[F]
  ): WorkerService[F] =
    new WorkerService[F] {

      private def slots(): F[List[SlotState]] = {
        for {
          res <- Range(0, cfg.maxSlots).toList.parTraverse { i =>
                   executor.getSlotState(i)
                 }
          _ <- Logger[F].debug(s"Slot scan returned: $res")
        } yield res
      }

      override def state(): F[WorkerState] = for {
        slots <- slots()
      } yield WorkerState(cfg.id, slots)

    }

}
