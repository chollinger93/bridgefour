package com.chollinger.bridgefour.shared.jobs

import scala.language.postfixOps

import cats.effect.kernel.Async
import cats.implicits._
import com.chollinger.bridgefour.shared.models.Job._
import io.circe._
import io.circe.syntax._
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf

// TODO: See all the other "TODOs" in this file + the other job files (there are 8) - this is a placeholder

/** Runs on leader. Reads each input file and returns a result as String, because of the shitty way we construct these
  * tasks. Once the system accepts JARs, this all goes into the trash
  */
trait LeaderJob[F[_]] {

  def job: JobDetails

  protected def allFiles: List[FilePath] = job.completedTasks.map(_.outputFile)

  // Calculate results across all files. This should be a quick operation, comparable to a Spark collect() to the SparkMaster node
  def collectResults(): F[Json]

}
