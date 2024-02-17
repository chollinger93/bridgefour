package com.chollinger.bridgefour.spren.services

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.data.Kleisli
import cats.effect.*
import cats.effect.kernel.Fiber
import cats.syntax.all.*
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.shared.background.BackgroundWorker.BackgroundWorkerResult
import com.chollinger.bridgefour.shared.background.BackgroundWorker.FiberContainer
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.background.BackgroundWorkerService
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.TaskId
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.chollinger.bridgefour.spren.TestUtils.Jobs.FakeJobCreator
import com.chollinger.bridgefour.spren.TestUtils.*
import com.chollinger.bridgefour.spren.models.Config
import com.chollinger.bridgefour.spren.models.Config.ServiceConfig
import com.chollinger.bridgefour.spren.programs.TaskExecutorService
import com.chollinger.bridgefour.spren.services.WorkerService
import com.comcast.ip4s.*
import fs2.io.net.Network
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger as Http4sLogger
import org.http4s.HttpApp
import org.http4s.Request
import org.http4s.Response
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger

import concurrent.duration.DurationDouble

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
    val taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, FakeJobCreator())
    val workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
    for {
      s <- workerSrv.state()
      _ = assertEquals(
            s,
            WorkerState(
              id = workerId,
              slots = List(usedSlot, openSlot),
              allSlots = List(slotId, openSlotId),
              availableSlots = List(openSlotId)
            )
          )
    } yield ()
  }

  // At this point, we're re-testing the BackgroundWorkerSuite, which one could argue is unnecessary, but it's also
  // concurrency, and concurrency is a horror show
  test("WorkerService should work with a real background worker, returning a valid WorkerState") {
    val taskIdTuple = TaskIdTuple(taskId, jobId)
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, BackgroundTaskState, TaskId]]()
      bgSrv     = BackgroundWorkerService.make[IO, BackgroundTaskState, TaskId](bg)
      taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, FakeJobCreator())
      workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
      execSrv   = TaskExecutorService.make[IO](sprenCfg, bgSrv, JobCreatorService.make())
      // Start a task
      statusMap <- execSrv.start(List(delayedTask(1)))
      _          = assertEquals(statusMap, Map(200 -> ExecutionStatus.InProgress))
      // Get status
      state <- workerSrv.state()
      _     <- Logger[IO].info(s"state: $state")
      _ = assertEquals(
            state,
            WorkerState(
              id = workerId,
              slots = List(
                // One running, with the delayedTask's taskIdTuple
                SlotState(slotId, ExecutionStatus.InProgress),
                // One available
                SlotState(openSlotId, ExecutionStatus.Missing)
              ),
              allSlots = List(slotId, openSlotId),
              // One slot should be occupied
              availableSlots = List(openSlotId)
            )
          )
    } yield ()
  }

  test("WorkerService should yield an empty state if no processing happened yet") {
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, BackgroundTaskState, TaskId]]()
      bgSrv     = BackgroundWorkerService.make[IO, BackgroundTaskState, TaskId](bg)
      taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, FakeJobCreator())
      workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
      // Get status
      state <- workerSrv.state()
      _ = assertEquals(
            state,
            WorkerState(
              id = workerId,
              slots = List(
                // Nothing running
                SlotState(slotId, ExecutionStatus.Missing),
                SlotState(openSlotId, ExecutionStatus.Missing)
              ),
              allSlots = List(slotId, openSlotId),
              // Both are available
              availableSlots = List(slotId, openSlotId)
            )
          )
    } yield ()
  }

  test("WorkerService should return a valid state after processing is done") {
    val taskIdTuple = TaskIdTuple(taskId, jobId)
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, BackgroundTaskState, TaskId]]()
      bgSrv     = BackgroundWorkerService.make[IO, BackgroundTaskState, TaskId](bg)
      taskSrv   = TaskExecutorService.make(sprenCfg, bgSrv, FakeJobCreator())
      workerSrv = WorkerService.make[IO](sprenCfg, taskSrv)
      execSrv   = TaskExecutorService.make[IO](sprenCfg, bgSrv, JobCreatorService.make())
      // Start a task
      statusMap <- execSrv.start(List(sampleTask))
      _          = assertEquals(statusMap, Map(200 -> ExecutionStatus.InProgress))
      // Block until the task completes to make sure we get no race conditions
      _ <- bgSrv.getResult(sampleTask.slotId.id)
      // Get status
      state <- workerSrv.state()
      _ = assertEquals(
            state,
            WorkerState(
              id = workerId,
              slots = List(
                // Nothing running, but slot 0 reports the last task it finished
                SlotState(slotId, ExecutionStatus.Done),
                SlotState(openSlotId, ExecutionStatus.Missing)
              ),
              allSlots = List(slotId, openSlotId),
              // Both are available
              availableSlots = List(slotId, openSlotId)
            )
          )
    } yield ()
  }

}
