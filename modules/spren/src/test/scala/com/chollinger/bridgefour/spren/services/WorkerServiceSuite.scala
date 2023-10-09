package com.chollinger.bridgefour.spren.services

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.data.Kleisli
import cats.effect.*
import cats.effect.kernel.Fiber
import cats.syntax.all.*
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.spren.TestUtils.Jobs.FakeJobCreator
import com.chollinger.bridgefour.spren.TestUtils.*
import com.chollinger.bridgefour.spren.models.Config
import com.chollinger.bridgefour.spren.models.Config.ServiceConfig
import com.chollinger.bridgefour.spren.programs.TaskExecutorService
import com.chollinger.bridgefour.spren.services.WorkerService
import com.chollinger.bridgefour.shared.background.BackgroundWorker.BackgroundWorkerResult
import com.chollinger.bridgefour.shared.background.BackgroundWorker.FiberContainer
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.background.BackgroundWorkerService
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.RockConfig
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job.TaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.shared.models.Worker.SlotState
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
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

  private def MockBackgroundWorkerService(t: TaskState, inProgressSlot: SlotId) =
    new BackgroundWorker[IO, TaskState, SlotTaskIdTuple] {

      override def start(key: Long, f: IO[TaskState], meta: Option[SlotTaskIdTuple] = None): IO[ExecutionStatus] = ???
      override def get(key: Long): IO[Option[FiberContainer[IO, TaskState, SlotTaskIdTuple]]]                    = ???
      override def getResult(key: Long): IO[BackgroundWorkerResult[IO, TaskState, SlotTaskIdTuple]] =
        if (key == inProgressSlot) IO(BackgroundWorkerResult(Left(ExecutionStatus.InProgress), None))
        else IO(BackgroundWorkerResult(Right(t), None))
      override def probeResult(
          key: Long,
          timeout: FiniteDuration
      ): IO[BackgroundWorkerResult[IO, TaskState, SlotTaskIdTuple]] =
        if (key == inProgressSlot) IO(BackgroundWorkerResult(Left(ExecutionStatus.InProgress), None))
        else IO(BackgroundWorkerResult(Right(t), None))

    }

  private val taskIdTuple: TaskIdTuple = TaskIdTuple(taskId, jobId)
  private val openSlotId: SlotId       = slotId + 1

  test("WorkerService.state should return a correct state") {
    // Expected
    val task = TaskState(taskIdTuple, status = ExecutionStatus.InProgress)
    val usedSlot =
      SlotState(
        SlotIdTuple(slotId, workerId),
        available = false,
        status = ExecutionStatus.InProgress,
        // This is a far from perfect test, because the actual BackgroundWorker would set this value to something useful
        // TODO: rethink ExecutionStatus responsibilities
        taskId = None
      )
    val openSlot =
      SlotState(
        SlotIdTuple(openSlotId, workerId),
        available = true,
        status = ExecutionStatus.Done,
        taskId = Some(taskIdTuple)
      )
    // Services
    val bgSrv     = MockBackgroundWorkerService(task, slotId)
    val taskSrv   = TaskExecutorService.make(rockCfg, bgSrv, FakeJobCreator())
    val workerSrv = WorkerService.make[IO](rockCfg, taskSrv)
    for {
      s <- workerSrv.state()
      _ = assertEquals(
            s,
            WorkerState(
              id = workerId,
              slots = List(usedSlot, openSlot),
              allSlots = List(slotId, openSlotId),
              availableSlots = List(openSlotId),
              // Similar to the comment above, this test is somewhat BS, since it won't report a correct status
              // and the service filters on Status == InProgress & TaskId.exists
              runningTasks = List()
            )
          )
    } yield ()
  }

  // At this point, we're re-testing the BackgroundWorkerSuite, which one could argue is unnecessary, but it's also
  // concurrency, and concurrency is a horror show
  test("WorkerService should work with a real background worker, returning a valid WorkerState") {
    val taskIdTuple = TaskIdTuple(taskId, jobId)
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, TaskState, SlotTaskIdTuple]]()
      bgSrv     = BackgroundWorkerService.make[IO, TaskState, SlotTaskIdTuple](bg)
      taskSrv   = TaskExecutorService.make(rockCfg, bgSrv, FakeJobCreator())
      workerSrv = WorkerService.make[IO](rockCfg, taskSrv)
      execSrv   = TaskExecutorService.make[IO](rockCfg, bgSrv, JobCreatorService.make())
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
                SlotState(SlotIdTuple(slotId, workerId), false, ExecutionStatus.InProgress, Some(taskIdTuple)),
                // One available
                SlotState(SlotIdTuple(openSlotId, workerId), true, ExecutionStatus.Missing, None)
              ),
              allSlots = List(slotId, openSlotId),
              // One slot should be occupied
              availableSlots = List(openSlotId),
              // The delayed task should still be running
              runningTasks = List(taskIdTuple)
            )
          )
    } yield ()
  }

  test("WorkerService should yield an empty state if no processing happened yet") {
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, TaskState, SlotTaskIdTuple]]()
      bgSrv     = BackgroundWorkerService.make[IO, TaskState, SlotTaskIdTuple](bg)
      taskSrv   = TaskExecutorService.make(rockCfg, bgSrv, FakeJobCreator())
      workerSrv = WorkerService.make[IO](rockCfg, taskSrv)
      // Get status
      state <- workerSrv.state()
      _ = assertEquals(
            state,
            WorkerState(
              id = workerId,
              slots = List(
                // Nothing running
                SlotState(SlotIdTuple(slotId, workerId), true, ExecutionStatus.Missing, None),
                SlotState(SlotIdTuple(openSlotId, workerId), true, ExecutionStatus.Missing, None)
              ),
              allSlots = List(slotId, openSlotId),
              // Both are available
              availableSlots = List(slotId, openSlotId),
              // Nothing should be running
              runningTasks = List()
            )
          )
    } yield ()
  }

  test("WorkerService should return a valid state after processing is done") {
    val taskIdTuple = TaskIdTuple(taskId, jobId)
    for {
      bg       <- InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, TaskState, SlotTaskIdTuple]]()
      bgSrv     = BackgroundWorkerService.make[IO, TaskState, SlotTaskIdTuple](bg)
      taskSrv   = TaskExecutorService.make(rockCfg, bgSrv, FakeJobCreator())
      workerSrv = WorkerService.make[IO](rockCfg, taskSrv)
      execSrv   = TaskExecutorService.make[IO](rockCfg, bgSrv, JobCreatorService.make())
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
                SlotState(SlotIdTuple(slotId, workerId), true, ExecutionStatus.Done, Some(taskIdTuple)),
                SlotState(SlotIdTuple(openSlotId, workerId), true, ExecutionStatus.Missing, None)
              ),
              allSlots = List(slotId, openSlotId),
              // Both are available
              availableSlots = List(slotId, openSlotId),
              // Nothing should be running
              runningTasks = List()
            )
          )
    } yield ()
  }

}
