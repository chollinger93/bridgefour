package com.chollinger.bridgefour.kaladin.programs

import scala.collection.immutable.List

import cats.effect.{IO, Sync}
import com.chollinger.bridgefour.kaladin.Jobs
import com.chollinger.bridgefour.kaladin.TestUtils._
import com.chollinger.bridgefour.kaladin.services._
import com.chollinger.bridgefour.kaladin.state.JobDetailsStateMachine
import com.chollinger.bridgefour.shared.models.IDs.{JobId, SlotIdTuple, TaskIdTuple}
import com.chollinger.bridgefour.shared.models.Job._
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task._
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
class JobDetailsStateMachineSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  val cfgParser: JobConfigParser[IO]                                  = FileEndingSortedJobConfigParserService.make()
  val baseId                                                          = 200
  val ids: IdMaker[IO, JobId]                                         = MockIDMaker.make(baseId)
  val splitter: JobSplitter[IO]                                       = JobSplitterService.make[IO]()

  val jCfg: UserJobConfig = UserJobConfig(
    name = "unit-test", jobClass = Jobs.sampleJobClass, input = "fakedir", output = "fakedir", userSettings = Map()
  )
  val sCfg: SystemJobConfig = SystemJobConfig.apply(baseId, jCfg)
  val t1: AssignedTaskConfig = AssignedTaskConfig(
    taskId = TaskIdTuple(0, 100),
    slotId = SlotIdTuple(200, 300),
    input = "sample1",
    output = "out",
    jobClass = Jobs.sampleJobClass,
    userSettings = Map()
  )
  val t2: AssignedTaskConfig    = t1.copy(taskId = TaskIdTuple(1, 100), slotId = SlotIdTuple(201, 300), input = "sample2")
  val ut1: UnassignedTaskConfig = UnassignedTaskConfig(t1)
  val ut2: UnassignedTaskConfig = UnassignedTaskConfig(t2)
  test("JobDetailsStateMachine creates a  valid initial state") {
    val stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
    for {
      dir    <- createTmpDir("jobcontrollersuite")
      outDir <- createTmpDir("jobcontrollersuite-out")
      f1     <- createTmpFile(dir, suffix = ".f1")
      jCfg = UserJobConfig(
               name = "unit-test",
               jobClass = Jobs.sampleJobClass,
               input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath,
               userSettings = Map("my" -> "config")
             )
      sCfg = SystemJobConfig.apply(baseId, jCfg)
      notAssignedTasks = List(
                           UnassignedTaskConfig(
                             input = f1.getAbsolutePath,
                             output = outDir.getAbsolutePath,
                             jobClass = Jobs.sampleJobClass,
                             userSettings = Map("my" -> "config")
                           )
                         )
      init <- stateMachine.initialState(jCfg)
      _ = assertEquals(
            init,
            JobDetails(
              jobId = baseId, jobConfig = sCfg, executionStatus = ExecutionStatus.NotStarted,
              assignmentStatus = AssignmentStatus.NotAssigned, assignedTasks = List.empty, openTasks = notAssignedTasks,
              completedTasks = List.empty
            )
          )
    } yield ()
  }

  test("JobDetailsStateMachine creates an empty initial state when no data is present") {
    val stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
    for {
      init <- stateMachine.initialState(jCfg)
      _ = assertEquals(
            init,
            JobDetails(
              jobId = baseId, jobConfig = sCfg, executionStatus = ExecutionStatus.NotStarted,
              assignmentStatus = AssignmentStatus.NotAssigned, assignedTasks = List.empty, openTasks = List.empty,
              completedTasks = List.empty
            )
          )
    } yield ()
  }

  test("JobDetailsStateMachine moves through a happy path execution without flaws") {
    val stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
    val startJd = JobDetails(
      jobId = baseId, jobConfig = sCfg, executionStatus = ExecutionStatus.NotStarted,
      assignmentStatus = AssignmentStatus.NotAssigned, assignedTasks = List.empty,
      openTasks = List(UnassignedTaskConfig(t1)), completedTasks = List.empty
    )
    // No change in progress, reports job as stuck
    val s1 = stateMachine.transition(jd = startJd, Map())
    assertEquals(s1, startJd.copy(executionStatus = ExecutionStatus.Halted))
    // Job in progress
    val s2 = stateMachine.transition(jd = s1, Map(t1 -> ExecutionStatus.InProgress))
    assertEquals(
      s2,
      s1.copy(
        executionStatus = ExecutionStatus.InProgress,
        assignmentStatus = AssignmentStatus.FullyAssigned,
        assignedTasks = List(t1),
        openTasks = List.empty
      )
    )
    // Job still in progress
    val s3 = stateMachine.transition(jd = s2, Map(t1 -> ExecutionStatus.InProgress))
    assertEquals(s3, s2)
    // Job completes
    val s4 = stateMachine.transition(jd = s3, Map(t1 -> ExecutionStatus.Done))
    assertEquals(
      s4,
      s3.copy(
        executionStatus = ExecutionStatus.Done, assignmentStatus = AssignmentStatus.NotAssigned,
        assignedTasks = List.empty, openTasks = List.empty, completedTasks = List(t1)
      )
    )
    // Another transition attempt makes no difference
    val s5 = stateMachine.transition(jd = s4, Map(t1 -> ExecutionStatus.Done))
    assertEquals(s5, s4)
  }

  test("JobDetailsStateMachine moves through with transient failures") {
    val stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
    val startJd = JobDetails(
      jobId = baseId,
      jobConfig = sCfg,
      executionStatus = ExecutionStatus.NotStarted,
      assignmentStatus = AssignmentStatus.NotAssigned,
      assignedTasks = List.empty,
      openTasks = List(ut1, ut2),
      completedTasks = List.empty
    )
    // Job partially in progress
    val s1 = stateMachine.transition(jd = startJd, Map(t1 -> ExecutionStatus.InProgress))
    assertEquals(
      s1,
      startJd.copy(
        executionStatus = ExecutionStatus.InProgress,
        assignmentStatus = AssignmentStatus.PartiallyAssigned,
        assignedTasks = List(t1),
        openTasks = List(ut2)
      )
    )
    // Job now fully in Progress
    val s2 =
      stateMachine.transition(
        jd = s1,
        Map(t1 -> ExecutionStatus.InProgress, t2 -> ExecutionStatus.InProgress)
      )
    assertEquals(
      s2,
      s1.copy(
        executionStatus = ExecutionStatus.InProgress,
        assignmentStatus = AssignmentStatus.FullyAssigned,
        assignedTasks = List(t1, t2),
        openTasks = List.empty
      )
    )
    // Still in progress, but one status doesn't get reported, which causes no material change
    val s3 =
      stateMachine.transition(
        jd = s2,
        Map(t1 -> ExecutionStatus.InProgress)
      )
    assertEquals(s3, s2)
    assertEquals(s3.openTasks.size, 0)
    assertEquals(s3.assignedTasks.size, 2)
    // One task now fails
    val s4 =
      stateMachine.transition(
        jd = s3,
        Map(t1 -> ExecutionStatus.InProgress, t2 -> ExecutionStatus.Error)
      )
    assertEquals(
      s4,
      s3.copy(
        assignmentStatus = AssignmentStatus.PartiallyAssigned,
        assignedTasks = List(t1),
        openTasks = List(ut2)
      )
    )
    // Task keeps struggling, but Error, Open, Halted, NotStarted all have the same effect, the task gets moved
    // into the open state for the controller to start
    val s5 =
      stateMachine.transition(
        jd = s4,
        Map(t1 -> ExecutionStatus.InProgress, t2 -> ExecutionStatus.Halted)
      )
    assertEquals(s5, s4)
    // Both tasks fail, Job is now Halted
    val s6 =
      stateMachine.transition(
        jd = s5,
        Map(t1 -> ExecutionStatus.Error, t2 -> ExecutionStatus.Error)
      )
    assertEquals(
      s6,
      s5.copy(
        executionStatus = ExecutionStatus.Halted,
        assignmentStatus = AssignmentStatus.NotAssigned,
        assignedTasks = List.empty,
        openTasks = List(ut2, ut1)
      )
    )
    // One finally completes, the other one still fails
    val s7 =
      stateMachine.transition(
        jd = s6,
        Map(t1 -> ExecutionStatus.Done, t2 -> ExecutionStatus.Error)
      )
    assertEquals(
      s7,
      s6.copy(
        executionStatus = ExecutionStatus.Halted, assignmentStatus = AssignmentStatus.NotAssigned,
        assignedTasks = List.empty, openTasks = List(ut2), completedTasks = List(t1)
      )
    )
    // Happy ending: Both complete
    val s8 =
      stateMachine.transition(
        jd = s7,
        Map(t2 -> ExecutionStatus.Done)
      )
    assertEquals(
      s8,
      s7.copy(
        executionStatus = ExecutionStatus.Done,
        assignmentStatus = AssignmentStatus.NotAssigned,
        assignedTasks = List.empty,
        openTasks = List.empty,
        completedTasks = List(t1, t2)
      )
    )
  }

}
