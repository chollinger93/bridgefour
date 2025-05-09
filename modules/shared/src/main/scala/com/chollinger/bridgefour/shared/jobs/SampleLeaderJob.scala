package com.chollinger.bridgefour.shared.jobs

import scala.language.postfixOps

import cats.effect.IO
import cats.implicits._
import com.chollinger.bridgefour.shared.models.Job._
import io.circe._
import io.circe.syntax._
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf

case class SampleLeaderJob(job: JobDetails) extends LeaderJob[IO] {

  given EntityDecoder[IO, String] = accumulatingJsonOf[IO, String]

  override def collectResults(): IO[Json] =
    IO.pure(job.jobId.asJson)

}
