package com.chollinger.bridgefour.rock.programs

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import cats.effect.kernel.Fiber
import cats.effect.{IO, Sync}
import com.chollinger.bridgefour.rock.TestUtils
import com.chollinger.bridgefour.rock.TestUtils.Jobs.FakeJobCreator
import com.chollinger.bridgefour.rock.TestUtils.*
import com.chollinger.bridgefour.rock.programs.TaskExecutorService
import com.chollinger.bridgefour.shared.background.BackgroundWorker.FiberContainer
import com.chollinger.bridgefour.shared.background.{BackgroundWorker, BackgroundWorkerService}
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.IDs.{SlotIdTuple, SlotTaskIdTuple, TaskIdTuple}
import com.chollinger.bridgefour.shared.models.Job.TaskState
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.shared.models.Worker.SlotState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TaskExecutorServiceSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  private val stateF                                                  = InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, TaskState, SlotTaskIdTuple]]()

  // TODO: this test can fail if the fiber doesn't finish in time. add Ref[IO]
  test("TaskExecutorService can start a job") {
    val taskIdTuple = TaskIdTuple(taskId, jobId)
    val slotIdTuple = SlotIdTuple(slotId, workerId)
    for {
      state     <- stateF
      bg         = BackgroundWorkerService.make[IO, TaskState, SlotTaskIdTuple](state)
      srv        = TaskExecutorService.make(rockCfg, bg, FakeJobCreator())
      statusMap <- srv.start(List(sampleTask))
      _          = assertEquals(statusMap, Map(taskId -> ExecutionStatus.InProgress))
      _         <- IO.println("Getting result")
      // Checking the underlying storage
      res <- bg.getResult(slotId)
      _    = assertEquals(res.res.toOption.get, TaskState(taskIdTuple, ExecutionStatus.Done))
      _    = assertEquals(res.meta.get.slot, slotIdTuple)
      // Checking the actual API
      status <- srv.getStatus(slotIdTuple)
      _       = assertEquals(status, ExecutionStatus.Done)
    } yield ()
  }

}
