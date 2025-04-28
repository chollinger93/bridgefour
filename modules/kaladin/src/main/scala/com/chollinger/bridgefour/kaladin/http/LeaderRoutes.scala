package com.chollinger.bridgefour.kaladin.http

import cats.effect.Concurrent
import cats.syntax.all._
import com.chollinger.bridgefour.kaladin.programs.JobController
import com.chollinger.bridgefour.kaladin.services.ClusterOverseer
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.{JobDetails, UserJobConfig}
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.{Job, WorkerStatus}
import io.circe.Json
import io.circe.disjunctionCodecs.encodeEither
import org.http4s._
import org.http4s.circe.{accumulatingJsonOf, jsonEncoderOf}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
case class LeaderRoutes[F[_]: Concurrent](controller: JobController[F], healthMonitor: ClusterOverseer[F])
    extends Http4sDsl[F]
    with Route[F] {

  protected val prefixPath: String                            = "/"
  given EntityDecoder[F, UserJobConfig]                       = accumulatingJsonOf[F, UserJobConfig]
  given EntityEncoder[F, JobDetails]                          = jsonEncoderOf[F, JobDetails]
  given EntityEncoder[F, List[JobDetails]]                    = jsonEncoderOf[F, List[JobDetails]]
  given EntityEncoder[F, ExecutionStatus]                     = jsonEncoderOf[F, ExecutionStatus]
  given EntityEncoder[F, Json]                                = jsonEncoderOf[F, Json]
  given EntityEncoder[F, Map[WorkerId, WorkerStatus]]         = jsonEncoderOf[F, Map[WorkerId, WorkerStatus]]
  given EntityEncoder[F, Either[ExecutionStatus, JobDetails]] = jsonEncoderOf[F, Either[ExecutionStatus, JobDetails]]
  given EntityEncoder[F, ClusterState]                        = jsonEncoderOf[F, ClusterState]

  protected def httpRoutes(): HttpRoutes[F] = {
    HttpRoutes.of[F] {
      // Job States
      case GET -> Root / "job" / "list"        => Ok(controller.listRunningJobs())
      case GET -> Root / "job" / IntVar(jobId) => Ok(controller.getJobDetails(jobId))
      // Job Lifecycle
      case req @ POST -> Root / "start" =>
        Ok(for {
          jCfg            <- req.as[UserJobConfig]
          res: JobDetails <- controller.submitJob(jCfg)
        } yield res)
      case PUT -> Root / "stop" / IntVar(jobId)   => Ok(controller.stopJob(jobId))
      case GET -> Root / "status" / IntVar(jobId) => Ok(controller.getJobResult(jobId))
      case GET -> Root / "data" / IntVar(jobId) =>
        controller
          .calculateResults(jobId)
          .flatMap {
            case Left(s)     => Ok(s)
            case Right(data) => Ok(data)
          }
      // Health
      // TODO: from cache
      case GET -> Root / "cluster" => Ok(healthMonitor.getClusterState())
    }
  }

  def routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes()
  )

}
