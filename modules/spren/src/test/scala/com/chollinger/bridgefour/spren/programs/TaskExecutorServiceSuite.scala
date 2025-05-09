package com.chollinger.bridgefour.spren.programs

import scala.language.postfixOps

import cats.effect.IO
import cats.effect.Sync
import com.chollinger.bridgefour.shared.background.BackgroundWorker.FiberContainer
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.background.BackgroundWorkerService
import com.chollinger.bridgefour.shared.jobs.BridgeFourJobCreatorService
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.chollinger.bridgefour.spren.TestUtils
import com.chollinger.bridgefour.spren.TestUtils._
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TaskExecutorServiceSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  private val stateF                                                  = InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, BackgroundTaskState, TaskId]]()

  // TODO: this test can fail if the fiber doesn't finish in time. add Ref[IO]
  test("TaskExecutorService can start a job") {
    for {
      state     <- stateF
      bg         = BackgroundWorkerService.make[IO, BackgroundTaskState, TaskId](state)
      creator    = BridgeFourJobCreatorService.make[IO]()
      srv        = TaskExecutorService.make(sprenCfg, bg, creator)
      statusMap <- srv.start(List(sampleTask))
      _          = assertEquals(statusMap, Map(taskId -> ExecutionStatus.InProgress))
      _         <- IO.println("Getting result")
      // Checking the underlying storage
      res <- bg.getResult(slotId)
      _    = assertEquals(res.res.toOption.get, BackgroundTaskState(taskId, ExecutionStatus.Done))
      _    = assertEquals(res.meta.get, taskId)
      // Checking the actual API
      status <- srv.getStatus(slotId)
      _       = assertEquals(status, ExecutionStatus.Done)
    } yield ()
  }

}
