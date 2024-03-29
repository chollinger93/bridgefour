package com.chollinger.bridgefour.kaladin.services

import java.io.File
import java.nio.file.Files

import scala.concurrent.duration.{DurationDouble, FiniteDuration}
import scala.language.postfixOps

import cats.data.Kleisli
import cats.effect.*
import cats.effect.kernel.Fiber
import cats.implicits.*
import cats.syntax.all.{toTraverseOps, _}
import cats.syntax.traverse.toTraverseOps
import cats.{Monad, Parallel}
import com.chollinger.bridgefour.kaladin.TestUtils.{createTmpDir, createTmpFile}
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.{BackgroundTaskState, UserJobConfig}
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.comcast.ip4s.*
import fs2.io.net.Network
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger
import org.http4s.{HttpApp, Request, Response}

class JobConfigParserSuite extends CatsEffectSuite {

  test("JobConfigParserService.splitJobIntoFiles should split valid files") {
    val srv = JobConfigParserService.make[IO]()
    for {
      dir    <- createTmpDir("jobcontrollersuite")
      outDir <- createTmpDir("jobcontrollersuite-out")
      files  <- Range(0, 10).toList.parTraverse(_ => createTmpFile(dir))
      cfg = UserJobConfig(
              name = "unit-test",
              jobClass = JobClass.SampleJob,
              input = dir.getAbsolutePath,
              output = outDir.getAbsolutePath,
              userSettings = Map()
            )
      listedFiles <- srv.splitJobIntoFiles(cfg)
      _           <- IO.println(listedFiles)
      _            = assertEquals(listedFiles.sorted, files.sorted)
      _            = assert(listedFiles.nonEmpty)
    } yield ()
  }

  test("JobConfigParserService.splitJobIntoFiles accepts empty dirs") {
    val srv = JobConfigParserService.make[IO]()
    val cfg = UserJobConfig(
      name = "unit-test",
      jobClass = JobClass.SampleJob,
      input = "fake",
      output = "fake",
      userSettings = Map()
    )
    for {
      listedFiles <- srv.splitJobIntoFiles(cfg)
      _            = assert(listedFiles.isEmpty)
    } yield ()
  }

}
