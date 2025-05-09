package com.chollinger.bridgefour.kaladin.services

import java.io.File

import scala.language.postfixOps

import cats.effect._
import cats.implicits._
import com.chollinger.bridgefour.kaladin.Jobs
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpDir
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpFile
import com.chollinger.bridgefour.shared.models.Job.UserJobConfig
import munit.CatsEffectSuite

class JobConfigParserSuite extends CatsEffectSuite {

  test("JobConfigParserService.splitJobIntoFiles should split valid files") {
    val srv = JobConfigParserService.make[IO]()
    for {
      dir    <- createTmpDir("jobcontrollersuite")
      outDir <- createTmpDir("jobcontrollersuite-out")
      files  <- Range(0, 10).toList.parTraverse(_ => createTmpFile(dir))
      cfg = UserJobConfig(
              name = "unit-test", jobClass = Jobs.sampleJobClass, input = dir.getAbsolutePath,
              output = outDir.getAbsolutePath, userSettings = Map()
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
      name = "unit-test", jobClass = Jobs.sampleJobClass, input = "fake", output = "fake", userSettings = Map()
    )
    for {
      listedFiles <- srv.splitJobIntoFiles(cfg)
      _            = assert(listedFiles.isEmpty)
    } yield ()
  }

}
