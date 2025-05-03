package com.chollinger.bridgefour.shared.jobs

import cats.effect.kernel.Async
import cats.implicits.*
import com.chollinger.bridgefour.shared.models.Job.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.EntityDecoder
import org.http4s.circe.accumulatingJsonOf

import scala.language.postfixOps

case class SampleLeaderJob[F[_]](job: JobDetails) extends LeaderJob[F] {

  given Async[F]                 = summon[Async[F]]
  given EntityDecoder[F, String] = accumulatingJsonOf[F, String]

  override def collectResults(): F[Json] =
    Async[F].pure(job.jobId.asJson)

}
