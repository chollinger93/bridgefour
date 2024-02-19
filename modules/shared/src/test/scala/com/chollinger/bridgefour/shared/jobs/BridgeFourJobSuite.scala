package com.chollinger.bridgefour.shared.jobs

import cats.effect.{IO, Resource, Sync}
import cats.effect.implicits.*
import cats.implicits.toTraverseOps
import cats.syntax.all.toTraverseOps
import cats.syntax.traverse.toTraverseOps
import com.chollinger.bridgefour.shared.jobs.DelayedWordCountBridgeFourJob
import com.chollinger.bridgefour.shared.models.IDs.{SlotIdTuple, TaskIdTuple}
import com.chollinger.bridgefour.shared.models.Job.{JobDetails, SystemJobConfig}
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import io.circe.*
import io.circe.generic.auto.*
import io.circe.literal.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.io.{File, PrintWriter}
import java.nio.file.Files
import scala.io.{BufferedSource, Source}
class BridgeFourJobSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  given EntityDecoder[IO, Map[String, Int]]                           = accumulatingJsonOf[IO, Map[String, Int]]
  private def read(in: String): IO[List[List[String]]] =
    Resource.make[IO, BufferedSource](IO(Source.fromFile(in)))(r => IO(r.close())).use { fn =>
      for {
        _   <- Logger[IO].debug(s"Reading: $in")
        lns <- IO(fn.getLines().toList.map(_.split(",").toList))
      } yield lns
    }

  test("DelayedWordCountBridgeFourJob works and matches leader job") {
    for {
      _   <- IO(Files.createTempDirectory("wordcount").toFile)
      in  <- IO(getClass.getResource("/wordcount.txt").getPath)
      out <- IO(Files.createTempDirectory("out"))
      cfg = AssignedTaskConfig(
              taskId = TaskIdTuple(0, 0),
              slotId = SlotIdTuple(0, 0),
              input = in,
              output = out.toAbsolutePath.toString,
              jobClass = JobClass.DelayedWordCountJob,
              userSettings = Map("timeout" -> "0")
            )
      jCfg = SystemJobConfig(
               id = 0,
               name = "",
               input = in,
               output = out.toAbsolutePath.toString,
               jobClass = JobClass.DelayedWordCountJob,
               userSettings = Map("timeout" -> "0")
             )
      jd     = JobDetails.empty(0, jCfg, List.empty).copy(completedTasks = List(cfg))
      job    = DelayedWordCountBridgeFourJob[IO](cfg)
      _     <- job.run()
      data  <- read(cfg.outputFile)
      counts = data.map(ln => (ln.head, ln.last.toInt)).toMap
      _ = assertEquals(
            counts,
            Map(
              "test"    -> 1,
              "world"   -> 2,
              "thing"   -> 1,
              "testing" -> 1,
              "hello"   -> 1
            )
          )
      leader = DelayedWordCountLeaderJob[IO](jd)
      res   <- leader.collectResults()
      _     <- Logger[IO].info(res.noSpacesSortKeys)
      _      = assertEquals(res.noSpacesSortKeys, counts.asJson.noSpacesSortKeys)
    } yield ()
  }

}
