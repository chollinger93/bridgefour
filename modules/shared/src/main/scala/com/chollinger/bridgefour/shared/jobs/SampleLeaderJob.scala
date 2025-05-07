package com.chollinger.bridgefour.shared.jobs

import cats.effect.IO
import cats.effect.kernel.Async
import cats.implicits.*
import com.chollinger.bridgefour.shared.models.Job.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf

import scala.language.postfixOps

case class SampleLeaderJob(job: JobDetails) extends LeaderJob[IO] {

  given EntityDecoder[IO, String] = accumulatingJsonOf[IO, String]

  override def collectResults(): IO[Json] =
    IO.pure(job.jobId.asJson)

}
