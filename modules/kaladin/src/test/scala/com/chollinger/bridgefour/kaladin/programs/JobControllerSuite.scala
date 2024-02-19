package com.chollinger.bridgefour.kaladin.programs

import java.io.File
import java.nio.file.Files

import scala.collection.immutable.List
import scala.concurrent.duration.DurationDouble
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.data.Kleisli
import cats.effect.*
import cats.effect.kernel.Fiber
import cats.effect.std.Mutex
import cats.effect.std.UUIDGen
import cats.implicits.*
import cats.syntax.all.*
import cats.syntax.traverse.toTraverseOps
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.kaladin.TestUtils.Http.*
import com.chollinger.bridgefour.kaladin.TestUtils.*
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.kaladin.services.*
import com.chollinger.bridgefour.kaladin.state.JobDetailsStateMachine
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.JobId
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.*
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.comcast.ip4s.*
import fs2.io.net.Network
import io.circe.*
import io.circe.generic.auto.*
import io.circe.literal.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
class JobControllerSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  given EntityDecoder[IO, WorkerState]                                = accumulatingJsonOf[IO, WorkerState]

  val cfgParser: JobConfigParser[IO] = FileEndingSortedJobConfigParserService.make()
  val ids: IdMaker[IO, JobId]        = MockIDMaker.make()
  test("JobController can start a job if workers are available") {
    // We define that slot 1 is in progress, which matches the halfUsedWorkerState
    val client = mockClient(halfUsedWorkerState, usedSlotIds = List(1))
    for {
      cfg    <- Config.load[IO]()
      dir    <- createTmpDir("jobcontrollersuite")
      outDir <- createTmpDir("jobcontrollersuite-out")
      f1     <- createTmpFile(dir, suffix = ".f1")
      f2     <- createTmpFile(dir, suffix = ".f2")
      jCfg = UserJobConfig(
               name = "unit-test",
               jobClass = JobClass.SampleJob,
               input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath,
               userSettings = Map()
             )
      wrkSrv       = WorkerOverseerService.make[IO](cfg, client)
      state       <- InMemoryPersistence.makeF[IO, JobId, JobDetails]()
      splitter     = JobSplitterService.make[IO]()
      stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
      leader       = LeaderCreatorService.make[IO]()
      lock        <- Mutex[IO]
      // Program
      srv  = JobControllerService(client, ids, wrkSrv, splitter, state, stateMachine, leader, lock, cfg)
      res <- srv.startJob(jCfg)
      _   <- IO.println(res.asJson.spaces2)
      // The mock environment has one available slot out of two total
      _ = assertEquals(
            res,
            JobDetails(
              jobId = jobId, // from mockID
              jobConfig = SystemJobConfig(jobId, jCfg),
              executionStatus = ExecutionStatus.InProgress,
              assignmentStatus = AssignmentStatus.PartiallyAssigned,
              assignedTasks = List(
                AssignedTaskConfig(
                  // From mock-random ID generator
                  taskId = TaskIdTuple(id = jobId, jobId = jobId),
                  slotId = SlotIdTuple(openSlot.id, halfUsedWorkerState.id),
                  input = f1.getAbsolutePath,
                  output = outDir.getAbsolutePath,
                  jobClass = JobClass.SampleJob,
                  userSettings = Map()
                )
              ),
              openTasks = List(
                UnassignedTaskConfig(
                  input = f2.getAbsolutePath,
                  outDir.getAbsolutePath,
                  jobClass = JobClass.SampleJob,
                  userSettings = Map()
                )
              ),
              completedTasks = List.empty
            )
          )
      // Check get job
      s <- srv.getJobResultAndUpdateState(jobId)
      _  = assertEquals(s, ExecutionStatus.InProgress)
      // Check that it matches the state
      s2 <- state.get(jobId)
      _   = assertEquals(s, s2.get.executionStatus)
      // No data results available
      r <- srv.calculateResults(jobId)
      _  = assertEquals(r.left.toOption.get, ExecutionStatus.InProgress)
    } yield ()
  }

  test("JobController state change is reflected") {
    // We define that slot 1 is in progress, which matches the halfUsedWorkerState
    val client = mockClient(halfUsedWorkerState, usedSlotIds = List(1))
    val doneWorkerState: WorkerState = WorkerState(
      id = 0,
      slots = List(usedSlot, openSlot)
//      allSlots = List(usedSlot.id, openSlot.id),
//      availableSlots = List(openSlot.id, openSlot.id)
//      runningTasks = List()
    )
    for {
      cfg    <- Config.load[IO]()
      dir    <- createTmpDir("jobcontrollersuite")
      outDir <- createTmpDir("jobcontrollersuite-out")
      // f1 ending => InProgress status from the mock API, f3 => Done
      _ <- createTmpFile(dir, suffix = ".f1")
      _ <- createTmpFile(dir, suffix = ".f3")
      jCfg = UserJobConfig(
               name = "unit-test",
               jobClass = JobClass.SampleJob,
               input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath,
               userSettings = Map()
             )
      wrkSrv       = WorkerOverseerService.make[IO](cfg, client)
      state       <- InMemoryPersistence.makeF[IO, JobId, JobDetails]()
      splitter     = JobSplitterService.make[IO]()
      stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
      leader       = LeaderCreatorService.make[IO]()
      lock        <- Mutex[IO]
      // Program
      srv = JobControllerService(client, ids, wrkSrv, splitter, state, stateMachine, leader, lock, cfg)
      _  <- srv.startJob(jCfg)
      // Check get job
      s <- srv.getJobResultAndUpdateState(jobId)
      _  = assertEquals(s, ExecutionStatus.InProgress)
      // The mock environment has one available slot out of two total - same as last test
      // Set a new client where everything is done
      srv = JobControllerService(
              mockClient(doneWorkerState, doneSlotIds = doneWorkerState.allSlotIds),
              ids,
              wrkSrv,
              splitter,
              state,
              stateMachine,
              leader,
              lock,
              cfg
            )
      // The job should now be done
      s <- srv.getJobResultAndUpdateState(jobId)
      _  = assertEquals(s, ExecutionStatus.Done)
    } yield ()
  }

  test("JobController can get results for completed jobs via checkAndUpdateJobProgress + calculateResults") {
    // This job will be immediately report "done", but calling checkAndUpdateJobProgress should not cause errors
    val client = mockClient(halfUsedWorkerState, usedSlotIds = List(1))
    for {
      cfg    <- Config.load[IO]()
      dir    <- createTmpDir("jobcontrollersuite")
      outDir <- createTmpDir("jobcontrollersuite-out")
      _      <- createTmpFile(dir, suffix = ".f3")
      jCfg = UserJobConfig(
               name = "unit-test",
               jobClass = JobClass.SampleJob,
               input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath,
               userSettings = Map()
             )
      wrkSrv       = WorkerOverseerService.make[IO](cfg, client)
      state       <- InMemoryPersistence.makeF[IO, JobId, JobDetails]()
      splitter     = JobSplitterService.make[IO]()
      stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
      leader       = LeaderCreatorService.make[IO]()
      lock        <- Mutex[IO]
      // Program
      srv = JobControllerService(client, ids, wrkSrv, splitter, state, stateMachine, leader, lock, cfg)
      _  <- srv.startJob(jCfg)
      // Check get job
      s <- srv.getJobResultAndUpdateState(jobId)
      _  = assertEquals(s, ExecutionStatus.Done)
      // Check that it matches the state
      s2 <- state.get(jobId)
      _   = assertEquals(s, s2.get.executionStatus)
      // Now update the progress a bunch of times, which should be a NoOp
      _ <- srv.getJobResultAndUpdateState(jobId)
      // Get results
      r <- srv.calculateResults(jobId)
      // SampleLeaderJob just returns the jobId
      _ = assertEquals(r.toOption.get.noSpaces, "100")
    } yield ()
  }

}
