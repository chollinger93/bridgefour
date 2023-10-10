package com.chollinger.bridgefour.kaladin.http

import cats.Monad
import cats.effect.kernel.Sync
import cats.effect.Async
import cats.effect.Concurrent
import cats.syntax.all.*
import com.chollinger.bridgefour.kaladin.programs.JobController
import com.chollinger.bridgefour.kaladin.services.HealthMonitorService
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.JobDetails
import com.chollinger.bridgefour.shared.models.Job.UserJobConfig
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.shared.models.Job
import com.chollinger.bridgefour.shared.models.WorkerStatus
import com.comcast.ip4s.*
import fs2.io.net.Network
import io.circe.Json
import io.circe.disjunctionCodecs.encodeEither
import org.http4s.*
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
case class LeaderRoutes[F[_]: Concurrent](controller: JobController[F], healthMonitor: HealthMonitorService[F])
    extends Http4sDsl[F]
    with Route[F] {

  protected val prefixPath: String                            = "/"
  given EntityDecoder[F, UserJobConfig]                       = accumulatingJsonOf[F, UserJobConfig]
  given EntityEncoder[F, JobDetails]                          = jsonEncoderOf[F, JobDetails]
  given EntityEncoder[F, ExecutionStatus]                     = jsonEncoderOf[F, ExecutionStatus]
  given EntityEncoder[F, Json]                                = jsonEncoderOf[F, Json]
  given EntityEncoder[F, Map[WorkerId, WorkerStatus]]         = jsonEncoderOf[F, Map[WorkerId, WorkerStatus]]
  given EntityEncoder[F, Either[ExecutionStatus, JobDetails]] = jsonEncoderOf[F, Either[ExecutionStatus, JobDetails]]

  protected def httpRoutes(): HttpRoutes[F] = {
    HttpRoutes.of[F] {
      case GET -> Root / "list" / IntVar(jobId) => ???
      case GET -> Root / "listAll"              => ???
      case GET -> Root / "job" / IntVar(jobId)  => Ok(controller.getJobDetails(jobId))
      case req @ POST -> Root / "start" =>
        Ok(for {
          jCfg            <- req.as[UserJobConfig]
          res: JobDetails <- controller.startJob(jCfg)
        } yield res)
      case PUT -> Root / "stop" / IntVar(jobId)    => Ok(controller.stopJob(jobId))
      case GET -> Root / "status" / IntVar(jobId)  => Ok(controller.getJobResult(jobId))
      case GET -> Root / "refresh" / IntVar(jobId) => Ok(controller.checkAndUpdateJobProgress(jobId))
      case GET -> Root / "data" / IntVar(jobId) =>
        controller
          .calculateResults(jobId)
          .flatMap {
            case Left(s)     => Ok(s)
            case Right(data) => Ok(data)
          }
      case GET -> Root / "cluster" => Ok(healthMonitor.checkClusterStatus())
    }
  }

  def routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes()
  )

}
