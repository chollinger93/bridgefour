package com.chollinger.bridgefour.spren.programs

import cats.Monad
import cats.effect.kernel.Sync
import cats.implicits._
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.jobs.BridgeFourJobCreator
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.typelevel.log4cats.Logger

/** An task executor service that maintains state internally, usually by maintaining a BackgroundWorker[F, TaskState,
  * SlotState], but other implementations that are stateful are possible
  *
  * @tparam F
  *   Effect
  */
trait TaskExecutor[F[_]] {

  def start(tasks: List[AssignedTaskConfig]): F[Map[TaskId, ExecutionStatus]]

  def getSlotState(id: SlotId): F[SlotState]
  def getStatus(id: SlotId): F[ExecutionStatus]

}

object TaskExecutorService {

  // TODO: capacity
  def make[F[_]: ThrowableMonadError: Sync: Monad: Logger](
      sCfg: SprenConfig,
      bg: BackgroundWorker[F, BackgroundTaskState, TaskId],
      jc: BridgeFourJobCreator[F]
  ): TaskExecutor[F] = new TaskExecutor[F]:

    val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]

    private def startTask(cfg: AssignedTaskConfig): F[(TaskId, ExecutionStatus)] = {
      val task = jc.makeJob(cfg.jobClass, cfg)
      for {
        _ <- Logger[F].debug(s"Starting worker task $task in slot ${cfg.slotId}")
        r <-
          err
            .handleError(bg.start(cfg.slotId.id, task.run(), Some(cfg.taskId.id)))(_ => ExecutionStatus.Error)
            .map(s => (cfg.taskId.id, s))
        _ <- Logger[F].info(s"Started worker task $task in slot ${cfg.slotId}: $r")
      } yield r

    }

    override def start(tasks: List[AssignedTaskConfig]): F[Map[TaskId, ExecutionStatus]] =
      tasks.traverse(c => startTask(c)).map(_.toMap)

    override def getSlotState(id: SlotId): F[SlotState] =
      bg.probeResult(id, sCfg.probingTimeout).map { r =>
        r.res match
          // The "result" for this operation is just another ExecutionStatus from the underlying task
          case Right(res)   => SlotState(id, status = res.status)
          case Left(status) => SlotState(id, status = status)
      }

    override def getStatus(id: SlotId): F[ExecutionStatus] =
      getSlotState(id).map(_.status)

}
