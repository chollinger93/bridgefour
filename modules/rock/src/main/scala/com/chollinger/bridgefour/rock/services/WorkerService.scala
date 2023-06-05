package com.chollinger.bridgefour.rock.services

import scala.language.postfixOps

import cats.effect.Sync
import cats.implicits.*
import cats.syntax.all.toTraverseOps
import cats.syntax.traverse.toTraverseOps
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.rock.programs.TaskExecutor
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.models.Config.RockConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.TaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.SlotState
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.Persistence
import org.typelevel.log4cats.Logger

import concurrent.duration.DurationDouble

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
      cfg: RockConfig,
      executor: TaskExecutor[F]
  ): WorkerService[F] =
    new WorkerService[F] {

      private def slots(): F[List[SlotState]] = {
        for {
          res <- Range(0, cfg.maxSlots).toList.parTraverse { i =>
                   executor.getSlotState(SlotIdTuple(i, cfg.id))
                 }
          _ <- Logger[F].debug(s"Slot scan returned: $res")
        } yield res
      }

      override def state(): F[WorkerState] = for {
        slots        <- slots()
        allSlotIds    = slots.map(_.id.id)
        unusedSlotIds = slots.filter(_.available).map(_.id.id)
        runningTasks  = slots.filter(_.status == ExecutionStatus.InProgress).flatMap(_.taskId)
      } yield WorkerState(
        id = cfg.id,
        slots = slots,
        allSlots = allSlotIds,
        availableSlots = unusedSlotIds,
        runningTasks = runningTasks
      )

    }

}
