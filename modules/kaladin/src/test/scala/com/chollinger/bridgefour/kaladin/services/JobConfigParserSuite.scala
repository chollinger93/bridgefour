package com.chollinger.bridgefour.kaladin.services

import java.io.File
import java.nio.file.Files

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.data.Kleisli
import cats.effect._
import cats.effect.kernel.Fiber
import cats.implicits.*
import cats.syntax.all.toTraverseOps
import cats.syntax.all._
import cats.syntax.traverse.toTraverseOps
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpDir
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpFile
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.Job.UserJobConfig
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
import org.http4s.HttpApp
import org.http4s.Request
import org.http4s.Response

import concurrent.duration.DurationDouble

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
