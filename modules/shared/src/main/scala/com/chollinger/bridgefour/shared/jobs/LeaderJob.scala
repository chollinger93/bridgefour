package com.chollinger.bridgefour.shared.jobs

import cats.effect.kernel.Async
import cats.effect.Concurrent
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Temporal
import cats.implicits.*
import cats.syntax.all.toTraverseOps
import cats.syntax.traverse.toTraverseOps
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.shared.jobs.JobClass
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import io.circe.*
import io.circe.generic.auto.*
import io.circe.literal.*
import io.circe.syntax.*
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf
import org.typelevel.log4cats.Logger

import java.io.File
import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.io.BufferedSource
import scala.io.Source
import scala.language.postfixOps

trait LeaderCreator[F[_]] {

  def makeJob(jd: JobDetails): LeaderJob[F]

}

object LeaderCreatorService {

  def make[F[_]: Async: Parallel: Logger](): LeaderCreator[F] = (jd: JobDetails) =>
    jd.jobConfig.jobClass match
      case JobClass.SampleJob           => SampleLeaderJob(jd)
      case JobClass.DelayedWordCountJob => DelayedWordCountLeaderJob(jd)
      case _                            => ???

}
// TODO: See all the other "TODOs" in this file + the other job files (there are 8) - this is a placeholder

/** Runs on leader. Reads each input file and returns a result as String, because of the shitty way we construct these
  * tasks. Once the system accepts JARs, this all goes into the trash
  */
trait LeaderJob[F[_]] {

  def jobClass: JobClass

  def job: JobDetails

  protected def allFiles: List[FilePath] = job.completedTasks.map(_.outputFile)

  // Calculate results across all files. This should be a quick operation, comparable to a Spark collect() to the SparkMaster node
  def collectResults(): F[Json]

}

case class DelayedWordCountLeaderJob[F[_]: Async: Parallel: Logger](job: JobDetails) extends LeaderJob[F] {

  given EntityDecoder[F, Map[String, Int]] = accumulatingJsonOf[F, Map[String, Int]]

  val jobClass: JobClass = JobClass.DelayedWordCountJob
  val aF: Async[F]       = implicitly[Async[F]]

  // What the world desperately needs are more shitty CSV parsers
  private def readFileAsCsv(in: FilePath): F[Map[String, Int]] = {
    Resource.make[F, BufferedSource](aF.blocking(Source.fromFile(in)))(r => aF.blocking(r.close())).use { fn =>
      for {
        _     <- Logger[F].debug(s"Reading: $in")
        lns   <- aF.blocking(fn.getLines().toList.map(_.split(",").toList))
        counts = lns.map(ln => (ln.head, ln.last.toInt)).sortBy(_._2).toMap
      } yield counts
    }
  }

  def collectResults(): F[Json] = for {
    counts <- allFiles.parTraverse(f => readFileAsCsv(f))
    res = counts.foldLeft(Map[String, Int]()) { case (m1, m2) =>
            m1 ++ m2.map { case (k, v) => k -> (v + m1.getOrElse(k, 0)) }
          }
  } yield res.asJson

}

case class SampleLeaderJob[F[_]: Async](job: JobDetails) extends LeaderJob[F] {

  given EntityDecoder[F, String]  = accumulatingJsonOf[F, String]
  override def jobClass: JobClass = JobClass.SampleJob

  override def collectResults(): F[Json] = Async[F].pure(job.jobId.asJson)

}
