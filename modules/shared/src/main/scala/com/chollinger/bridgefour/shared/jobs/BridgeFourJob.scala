package com.chollinger.bridgefour.shared.jobs

import cats.Monad
import cats.Parallel
import cats.effect.Concurrent
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.kernel.Async
import cats.implicits.*
import cats.syntax.all.toTraverseOps
import cats.syntax.traverse.toTraverseOps
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import io.circe.*
import io.circe.generic.auto.*
import io.circe.literal.*
import io.circe.syntax.*
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf
import org.latestbit.circe.adt.codec.JsonTaggedAdt
import org.typelevel.log4cats.Logger

import java.io.File
import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.io.BufferedSource
import scala.io.Source
import scala.language.postfixOps

// TODO: make it accept actual user jobs
// this is a simple workaround to map code in this repo (BridgeFourJobs) to a Job
enum JobClass derives JsonTaggedAdt.Codec {

  case SampleJob
  case DelayedWordCountJob
  case AlwaysOkJob

}

trait JobCreator[F[_]] {

  def makeJob(cfg: AssignedTaskConfig): BridgeFourJob[F]

}

object JobCreatorService {

  def make[F[_]: Async: Temporal: Logger](): JobCreator[F] = (cfg: AssignedTaskConfig) =>
    cfg.jobClass match
      case JobClass.SampleJob           => SampleBridgeFourJob(cfg)
      case JobClass.DelayedWordCountJob => DelayedWordCountBridgeFourJob(cfg)
      case _                            => ???

}

// TODO: this is super basic and not very abstracted
trait BridgeFourJob[F[_]] {

  // TODO: make it accept actual user jobs
  // this is a simple workaround to map code in this repo (BridgeFourJobs) to a Job
  def jobClass: JobClass

  /** Each job must work on exactly one file and write to one directory
    *
    * TODO: this limitations are for simple implementations and somewhat arbitrary
    */
  def config: AssignedTaskConfig

  /** Each job must be able to do computations on the output data */
  def run(): F[BackgroundTaskState]

}

// Does nothing but return "Done"
case class SampleBridgeFourJob[F[_]: Async](config: AssignedTaskConfig) extends BridgeFourJob[F] {

  val jobClass: JobClass = JobClass.SampleJob

  override def run(): F[BackgroundTaskState] =
    Async[F].blocking(BackgroundTaskState(id = config.taskId.id, status = ExecutionStatus.Done))

}

case class DelayedWordCountBridgeFourJob[F[_]: Async: Temporal: Logger](config: AssignedTaskConfig)
    extends BridgeFourJob[F] {

  val jobClass: JobClass = JobClass.DelayedWordCountJob
  val aF: Async[F]       = implicitly[Async[F]]

  private def wordCount(wrds: List[String], counts: Map[String, Int]): Map[String, Int] = {
    if (wrds.isEmpty) return counts
    wordCount(wrds.tail, counts.updatedWith(wrds.head)(ov => Some(ov.getOrElse(0) + 1)))
  }

  private def lineByLineWordCount(lns: List[List[String]], counts: Map[String, Int] = Map.empty): Map[String, Int] = {
    if (lns.isEmpty) return counts
    lineByLineWordCount(lns.tail, wordCount(lns.head, counts))
  }

  private def processFile(in: FilePath): F[Map[String, Int]] = {
    Resource.make[F, BufferedSource](aF.blocking(Source.fromFile(in)))(r => aF.blocking(r.close())).use { fn =>
      for {
        _     <- Logger[F].debug(s"Reading: $in")
        lns   <- aF.blocking(fn.getLines().toList)
        _     <- Logger[F].debug(s"Data: ${lns.size} lines")
        counts = lineByLineWordCount(lns.map(_.split(" ").toList))
        _     <- Logger[F].debug(s"Word count: $counts")
      } yield counts
    }
  }

  private def writeResult(out: FilePath, data: Map[String, Int]) =
    Resource.make[F, PrintWriter](aF.blocking(PrintWriter(out)))(w => aF.blocking(w.close())).use { w =>
      data.toList.traverse { case (k, v) => aF.blocking(w.write(s"$k,$v${System.lineSeparator}")) }
        >> Logger[F].info(s"Wrote ${data.size} lines to $out")
    }

  override def run(): F[BackgroundTaskState] = for {
    _        <- Logger[F].info(s"Starting job for file ${config.input}")
    waitTimeS = config.userSettings.getOrElse("timeout", "30").toInt
    _        <- Temporal[F].sleep(FiniteDuration(waitTimeS, TimeUnit.SECONDS))
    count    <- processFile(config.input)
    _        <- writeResult(config.outputFile, count)
    _        <- Logger[F].info(s"Done with job after $waitTimeS for file ${config.input}")
    r        <- Async[F].delay(BackgroundTaskState(id = config.taskId.id, status = ExecutionStatus.Done))
  } yield r

}
