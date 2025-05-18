package com.chollinger.bridgefour.kaladin.programs

import scala.language.postfixOps

import cats.effect._
import cats.effect.std.Mutex
import cats.implicits._
import com.chollinger.bridgefour.kaladin.Jobs
import com.chollinger.bridgefour.kaladin.TestUtils.Http._
import com.chollinger.bridgefour.kaladin.TestUtils._
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.kaladin.services._
import com.chollinger.bridgefour.kaladin.state.JobDetailsStateMachine
import com.chollinger.bridgefour.shared.jobs._
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job._
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task._
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import io.circe.syntax._
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.circe.accumulatingJsonOf
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
               name = "unit-test", jobClass = Jobs.sampleJobClass, input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath, userSettings = Map()
             )
      wState      <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache      <- WorkerCache.makeF[IO](cfg, wState)
      wrkSrv       = ClusterOverseer.make[IO](wCache, client)
      jState      <- InMemoryPersistence.makeF[IO, JobId, JobDetails]()
      cState      <- InMemoryPersistence.makeF[IO, ClusterId, ClusterState]()
      splitter     = JobSplitterService.make[IO]()
      stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
      leader       = LeaderCreatorService.make[IO]()
      lock        <- Mutex[IO]
      // Program
      srv      = JobControllerService(client, jState, stateMachine, leader, cfg)
      ctrl     = ClusterControllerImpl[IO](client, wrkSrv, splitter, jState, cState, wCache, ids, stateMachine, lock, cfg)
      initial <- srv.submitJob(jCfg)
      _        = assertEquals(initial.executionStatus, ExecutionStatus.NotStarted)
      _        = assertEquals(initial.assignmentStatus, AssignmentStatus.NotAssigned)
      // Do what the controller does in the background explicitly and poll state storage
      _   <- ctrl.rebalanceUnassignedTasks()
      res <- jState.get(jobId)
      // Controller
      _ <- IO.println(res.get.asJson.spaces2)
      // The mock environment has one available slot out of two total
      _ = assertEquals(
            res.get,
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
                  jobClass = Jobs.sampleJobClass,
                  userSettings = Map()
                )
              ),
              openTasks = List(
                UnassignedTaskConfig(
                  input = f2.getAbsolutePath,
                  outDir.getAbsolutePath,
                  jobClass = Jobs.sampleJobClass,
                  userSettings = Map()
                )
              ),
              completedTasks = List.empty
            )
          )
      // Check get job
      s <- srv.getJobResult(jobId)
      _  = assertEquals(s, ExecutionStatus.InProgress)
      // Check that it matches the state
      s2 <- jState.get(jobId)
      _   = assertEquals(s, s2.get.executionStatus)
      // No data results available
      r <- srv.calculateResults(jobId)
      _  = assertEquals(r.left.toOption.get, ExecutionStatus.InProgress)
      // List
      l <- srv.listRunningJobs()
      _  = assertEquals(l.size, 1)
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
               name = "unit-test", jobClass = Jobs.sampleJobClass, input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath, userSettings = Map()
             )
      wState      <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache      <- WorkerCache.makeF[IO](cfg, wState)
      wrkSrv       = ClusterOverseer.make[IO](wCache, client)
      jState      <- InMemoryPersistence.makeF[IO, JobId, JobDetails]()
      cState      <- InMemoryPersistence.makeF[IO, ClusterId, ClusterState]()
      splitter     = JobSplitterService.make[IO]()
      stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
      leader       = LeaderCreatorService.make[IO]()
      lock        <- Mutex[IO]
      // Program
      srv      = JobControllerService(client, jState, stateMachine, leader, cfg)
      ctrl     = ClusterControllerImpl[IO](client, wrkSrv, splitter, jState, cState, wCache, ids, stateMachine, lock, cfg)
      initial <- srv.submitJob(jCfg)
      _        = assertEquals(initial.executionStatus, ExecutionStatus.NotStarted)
      _        = assertEquals(initial.assignmentStatus, AssignmentStatus.NotAssigned)
      // Do what the controller does in the background explicitly and poll state storage
      _ <- ctrl.rebalanceUnassignedTasks()
      // Check get job
      s <- srv.getJobResult(jobId)
      _  = assertEquals(s, ExecutionStatus.InProgress)
      // The mock environment has one available slot out of two total - same as last test
      // Set a new client where everything is done
      mClient = mockClient(doneWorkerState, doneSlotIds = doneWorkerState.allSlotIds)
      srv = JobControllerService(
              mClient, jState, stateMachine, leader, cfg
            )
      ctrl = ClusterControllerImpl[IO](mClient, wrkSrv, splitter, jState, cState, wCache, ids, stateMachine, lock, cfg)
      // The job should now be done
      _ <- ctrl.rebalanceUnassignedTasks()
      _ <- ctrl.updateAllJobStates()
      s <- srv.getJobResult(jobId)
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
               name = "unit-test", jobClass = Jobs.sampleLeaderJobClass, input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath, userSettings = Map()
             )
      wState      <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache      <- WorkerCache.makeF[IO](cfg, wState)
      wrkSrv       = ClusterOverseer.make[IO](wCache, client)
      jState      <- InMemoryPersistence.makeF[IO, JobId, JobDetails]()
      cState      <- InMemoryPersistence.makeF[IO, ClusterId, ClusterState]()
      splitter     = JobSplitterService.make[IO]()
      stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
      leader       = LeaderCreatorService.make[IO]()
      lock        <- Mutex[IO]
      // Program
      srv      = JobControllerService(client, jState, stateMachine, leader, cfg)
      ctrl     = ClusterControllerImpl[IO](client, wrkSrv, splitter, jState, cState, wCache, ids, stateMachine, lock, cfg)
      initial <- srv.submitJob(jCfg)
      _        = assertEquals(initial.executionStatus, ExecutionStatus.NotStarted)
      _        = assertEquals(initial.assignmentStatus, AssignmentStatus.NotAssigned)
      // Do what the controller does in the background explicitly and poll state storage
      _   <- ctrl.rebalanceUnassignedTasks()
      res <- jState.get(jobId)
      // Check get job
      s <- srv.getJobResult(jobId)
      _  = assertEquals(s, ExecutionStatus.Done)
      // Check that it matches the state
      s2 <- jState.get(jobId)
      _   = assertEquals(s, s2.get.executionStatus)
      // Now update the progress a bunch of times, which should be a NoOp
      _ <- srv.getJobResult(jobId)
      // Get results
      r <- srv.calculateResults(jobId)
      // SampleLeaderJob just returns the jobId
      _ = assertEquals(r.toOption.get.noSpaces, "100")
    } yield ()
  }

}
