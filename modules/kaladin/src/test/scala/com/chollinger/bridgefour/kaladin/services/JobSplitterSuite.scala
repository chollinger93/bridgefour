package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import cats.effect.Sync
import cats.implicits._
import com.chollinger.bridgefour.kaladin.Jobs
import com.chollinger.bridgefour.kaladin.TestUtils.Http._
import com.chollinger.bridgefour.kaladin.TestUtils.MockIDMaker
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpDir
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpFile
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.Job._
import com.chollinger.bridgefour.shared.models.Task._
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import munit.CatsEffectSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger
class JobSplitterSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  test("splitJobIntoTasks assign valid tasks") {
    val ids = MockIDMaker.make()
    for {
      dir    <- createTmpDir("splitJobIntoTasks")
      outDir <- createTmpDir("splitJobIntoTasks-out")
      outDirS = outDir.getAbsolutePath
      jCfg = SystemJobConfig(
               id = 100, name = "test", jobClass = Jobs.sampleJobClass, input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath, userSettings = Map()
             )
      _              <- Range(0, 2).toList.parTraverse(_ => createTmpFile(dir))
      jobConfigParser = JobConfigParserService.make[IO]()
      files          <- jobConfigParser.splitJobIntoFiles(jCfg)
      workers         = List(halfUsedWorkerState)
      splitter        = JobSplitterService.make[IO]()
      // Split into files
      _         <- Logger[IO].debug(s"Files: $files")
      unassigned = files.map(f => UnassignedTaskConfig(f.getAbsolutePath, jCfg))
      jd         = JobDetails.empty(jobId = 100, jCfg, unassigned)
      // Run
      tasks <- splitter.splitJobIntoTasks(jd, workers, ids)
      // The result here is one assigned tasks for the first file, since we only have one worker
      _ = assertEquals(
            tasks,
            TaskAssignmentTuple(
              assigned = List(
                AssignedTaskConfig(
                  // From mock-random ID generator
                  taskId = TaskIdTuple(id = 100, jobId = 100),
                  slotId = SlotIdTuple(openSlot.id, halfUsedWorkerState.id),
                  input = files.head.getAbsolutePath,
                  output = outDirS,
                  jobClass = Jobs.sampleJobClass,
                  userSettings = Map()
                )
              ),
              notAssigned = List(
                UnassignedTaskConfig(
                  input = files.last.getAbsolutePath,
                  output = outDirS,
                  jobClass = Jobs.sampleJobClass,
                  userSettings = Map()
                )
              )
            )
          )
    } yield ()
  }

  test("splitJobIntoTasks assign valid tasks, even without workers") {
    val ids = MockIDMaker.make()
    for {
      dir    <- createTmpDir("splitJobIntoTasks")
      outDir <- createTmpDir("splitJobIntoTasks-out")
      jCfg = SystemJobConfig(
               id = 100, name = "test", jobClass = Jobs.sampleJobClass, input = dir.getAbsolutePath,
               output = outDir.getAbsolutePath, userSettings = Map()
             )
      workers  = List.empty[WorkerState]
      splitter = JobSplitterService.make[IO]()
      // Split into files
      files     <- Range(0, 5).toList.parTraverse(_ => createTmpFile(dir))
      unassigned = files.map(f => UnassignedTaskConfig(f.getAbsolutePath, jCfg))
      jd         = JobDetails.empty(jobId = 100, jCfg, unassigned)
      // Run
      tasks <- splitter.splitJobIntoTasks(jd, workers, ids)
      // No workers = all unassigned
      _ = assertEquals(tasks.assigned.size, 0)
      _ = assertEquals(tasks.notAssigned.size, 5)
    } yield ()
  }

}
