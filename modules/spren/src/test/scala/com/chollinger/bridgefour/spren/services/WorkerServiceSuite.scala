package com.chollinger.bridgefour.spren.services

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.effect._
import com.chollinger.bridgefour.shared.background.BackgroundWorker.{BackgroundWorkerResult, FiberContainer}
import com.chollinger.bridgefour.shared.background.{BackgroundWorker, BackgroundWorkerService}
import com.chollinger.bridgefour.shared.jobs._
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.chollinger.bridgefour.spren.TestUtils._
import com.chollinger.bridgefour.spren.programs.TaskExecutorService
import munit.CatsEffectSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

class WorkerServiceSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  private def MockBackgroundWorkerService(t: BackgroundTaskState, inProgressSlot: SlotId) =
    new BackgroundWorker[IO, BackgroundTaskState, TaskId] {

      override def start(
          key: Long,
          f: IO[BackgroundTaskState],
          meta: Option[TaskId] = None
      ): IO[ExecutionStatus] = ???
      override def get(key: Long): IO[Option[FiberContainer[IO, BackgroundTaskState, TaskId]]] = ???
      override def getResult(key: Long): IO[BackgroundWorkerResult[IO, BackgroundTaskState, TaskId]] =
        if (key == inProgressSlot) IO(BackgroundWorkerResult(Left(ExecutionStatus.InProgress), Some(taskId)))
        else IO(BackgroundWorkerResult(Right(t), Some(taskId)))
      override def probeResult(
          key: Long,
          timeout: FiniteDuration
      ): IO[BackgroundWorkerResult[IO, BackgroundTaskState, TaskId]] =
        if (key == inProgressSlot)
          IO(
            BackgroundWorkerResult(
              Left(ExecutionStatus.InProgress),
              Some(taskId)
            )
          )
        else IO(BackgroundWorkerResult(Right(t), Some(taskId)))

    }

  private val taskIdTuple: TaskIdTuple = TaskIdTuple(taskId, jobId)
  private val openSlotId: SlotId       = slotId + 1

  test("WorkerService.state should return a correct state") {
    // Expected
    val task = BackgroundTaskState(taskId, status = ExecutionStatus.Done)
    val usedSlot =
      SlotState(
        slotId,
        status = ExecutionStatus.InProgress
      )
    val openSlot =
      SlotState(
        openSlotId,
        status = ExecutionStatus.Done
      )
    // Services
    val bgSrv     = MockBackgroundWorkerService(task, slotId)
    val creator   = BridgeFourJobCreatorService.make[IO]()
    val taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, creator)
    val workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
    for {
      s <- workerSrv.state()
      expS = WorkerState(
               id = workerId,
               slots = List(usedSlot, openSlot)
             )
      _ = assertEquals(
            s,
            expS
          )
      _ = assertEquals(s.allSlotIds, List(slotId, openSlotId))
      _ = assertEquals(s.availableSlots, List(openSlotId))
    } yield ()
  }

  // At this point, we're re-testing the BackgroundWorkerSuite, which one could argue is unnecessary, but it's also
  // concurrency, and concurrency is a horror show
  test("WorkerService should work with a real background worker, returning a valid WorkerState") {
    val taskIdTuple = TaskIdTuple(taskId, jobId)
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, BackgroundTaskState, TaskId]]()
      bgSrv     = BackgroundWorkerService.make[IO, BackgroundTaskState, TaskId](bg)
      creator   = BridgeFourJobCreatorService.make[IO]()
      taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, creator)
      workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
      execSrv   = TaskExecutorService.make[IO](sprenCfg, bgSrv, creator)
      // Start a task
      statusMap <- execSrv.start(List(delayedTask(1)))
      _          = assertEquals(statusMap, Map(200 -> ExecutionStatus.InProgress))
      // Get status
      state <- workerSrv.state()
      _     <- Logger[IO].info(s"state: $state")
      expS = WorkerState(
               id = workerId,
               slots = List(
                 // One running, with the delayedTask's taskIdTuple
                 SlotState(slotId, ExecutionStatus.InProgress),
                 // One available
                 SlotState(openSlotId, ExecutionStatus.Missing)
               )
             )
      _ = assertEquals(
            state,
            expS
          )
      _ = assertEquals(state.allSlotIds, List(slotId, openSlotId))
      // One slot should be occupied
      _ = assertEquals(state.availableSlots, List(openSlotId))
    } yield ()
  }

  test("WorkerService should yield an empty state if no processing happened yet") {
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, BackgroundTaskState, TaskId]]()
      bgSrv     = BackgroundWorkerService.make[IO, BackgroundTaskState, TaskId](bg)
      creator   = BridgeFourJobCreatorService.make[IO]()
      taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, creator)
      workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
      // Get status
      state <- workerSrv.state()
      expS = WorkerState(
               id = workerId,
               slots = List(
                 // Nothing running
                 SlotState(slotId, ExecutionStatus.Missing),
                 SlotState(openSlotId, ExecutionStatus.Missing)
               )
             )
      _ = assertEquals(
            state,
            expS
          )
      _ = assertEquals(state.allSlotIds, List(slotId, openSlotId))
      // Both are available
      _ = assertEquals(state.availableSlots, List(slotId, openSlotId))
    } yield ()
  }

  test("WorkerService should return a valid state after processing is done") {
    val taskIdTuple = TaskIdTuple(taskId, jobId)
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, BackgroundTaskState, TaskId]]()
      bgSrv     = BackgroundWorkerService.make[IO, BackgroundTaskState, TaskId](bg)
      creator   = BridgeFourJobCreatorService.make[IO]()
      taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, creator)
      workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
      execSrv   = TaskExecutorService.make[IO](sprenCfg, bgSrv, creator)
      // Start a task
      statusMap <- execSrv.start(List(sampleTask))
      _          = assertEquals(statusMap, Map(200 -> ExecutionStatus.InProgress))
      // Block until the task completes to make sure we get no race conditions
      _ <- bgSrv.getResult(sampleTask.slotId.id)
      // Get status
      state <- workerSrv.state()
      expS = WorkerState(
               id = workerId,
               slots = List(
                 // Nothing running, but slot 0 reports the last task it finished
                 SlotState(slotId, ExecutionStatus.Done),
                 SlotState(openSlotId, ExecutionStatus.Missing)
               )
             )
      _ = assertEquals(
            state,
            expS
          )
      _ = assertEquals(state.allSlotIds, List(slotId, openSlotId))
      // Both are available
      _ = assertEquals(state.availableSlots, List(slotId, openSlotId))
    } yield ()
  }

}
