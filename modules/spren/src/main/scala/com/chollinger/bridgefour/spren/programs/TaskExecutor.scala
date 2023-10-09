package com.chollinger.bridgefour.spren.programs

import cats.Monad
import cats.effect.Concurrent
import cats.effect.implicits.*
import cats.effect.kernel.Sync
import cats.implicits.*
import cats.syntax.all.toFlatMapOps
import cats.syntax.all.toFunctorOps
import cats.syntax.all.toTraverseOps
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.traverse.toTraverseOps
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.background.BackgroundWorker.BackgroundWorkerResult
import com.chollinger.bridgefour.shared.jobs.BridgeFourJob
import com.chollinger.bridgefour.shared.jobs.JobClass
import com.chollinger.bridgefour.shared.jobs.JobCreator
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.TaskState
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.shared.models.Worker.SlotState
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import com.chollinger.bridgefour.spren.state.TaskExecutionStatusStateMachine
import org.typelevel.log4cats.Logger

/** An task executor service that maintains state internally, usually by maintaining a BackgroundWorker[F, TaskState,
  * SlotState], but other implementations that are stateful are possible
  *
  * @tparam F
  *   Effect
  */
trait TaskExecutor[F[_]] {

  def start(tasks: List[AssignedTaskConfig]): F[Map[TaskId, ExecutionStatus]]

  def getSlotState(id: SlotIdTuple): F[SlotState]
  def getStatus(id: SlotIdTuple): F[ExecutionStatus]

}

object TaskExecutorService {

  // TODO: capacity
  def make[F[_]: ThrowableMonadError: Sync: Monad: Logger](
      sCfg: SprenConfig,
      bg: BackgroundWorker[F, TaskState, SlotTaskIdTuple],
      jc: JobCreator[F]
  ): TaskExecutor[F] = new TaskExecutor[F]:

    val taskStateMachine: TaskExecutionStatusStateMachine = TaskExecutionStatusStateMachine()
    val err: ThrowableMonadError[F]                       = implicitly[ThrowableMonadError[F]]

    private def startTask(cfg: AssignedTaskConfig): F[(TaskId, ExecutionStatus)] = {
      val task = jc.makeJob(cfg)
      for {
        _ <- Logger[F].debug(s"Starting worker task $task in slot ${cfg.slotId}")
        r <-
          err
            .handleError(bg.start(cfg.slotId.id, task.run(), Some(SlotTaskIdTuple(cfg.slotId, cfg.taskId))))(_ =>
              ExecutionStatus.Error
            )
            .map(s => (cfg.taskId.id, s))
        _ <- Logger[F].info(s"Started worker task $task in slot ${cfg.slotId}: $r")
      } yield r

    }

    override def start(tasks: List[AssignedTaskConfig]): F[Map[TaskId, ExecutionStatus]] =
      tasks.traverse(c => startTask(c)).map(_.toMap)

    override def getSlotState(id: SlotIdTuple): F[SlotState] =
      bg.probeResult(id.id, sCfg.probingTimeout).map(r => taskStateMachine.transition(SlotState.empty(id), r))

    override def getStatus(id: SlotIdTuple): F[ExecutionStatus] =
      getSlotState(id).map(_.status)

}
