package com.chollinger.bridgefour.kaladin.http

import cats.effect.Concurrent
import cats.syntax.all.*
import com.chollinger.bridgefour.kaladin.programs.JobController
import com.chollinger.bridgefour.kaladin.services.ClusterOverseer
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.JobDetails
import com.chollinger.bridgefour.shared.models.Job.UserJobConfig
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Job
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.models.WorkerStatus
import io.circe.Json
import io.circe.disjunctionCodecs.encodeEither
import org.http4s.*
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
case class LeaderRoutes[F[_]: Concurrent](controller: JobController[F], clusterOverseer: ClusterOverseer[F])
    extends Http4sDsl[F]
    with Route[F] {

  protected val prefixPath: String      = "/"
  given EntityDecoder[F, UserJobConfig] = accumulatingJsonOf[F, UserJobConfig]
  given EntityDecoder[F, WorkerConfig]  = accumulatingJsonOf[F, WorkerConfig]

  given EntityEncoder[F, JobDetails]                          = jsonEncoderOf[F, JobDetails]
  given EntityEncoder[F, List[JobDetails]]                    = jsonEncoderOf[F, List[JobDetails]]
  given EntityEncoder[F, ExecutionStatus]                     = jsonEncoderOf[F, ExecutionStatus]
  given EntityEncoder[F, Json]                                = jsonEncoderOf[F, Json]
  given EntityEncoder[F, Map[WorkerId, WorkerStatus]]         = jsonEncoderOf[F, Map[WorkerId, WorkerStatus]]
  given EntityEncoder[F, Either[ExecutionStatus, JobDetails]] = jsonEncoderOf[F, Either[ExecutionStatus, JobDetails]]
  given EntityEncoder[F, ClusterState]                        = jsonEncoderOf[F, ClusterState]
  given EntityEncoder[F, WorkerState]                         = jsonEncoderOf[F, WorkerState]
  given EntityEncoder[F, Boolean]                             = jsonEncoderOf[F, Boolean]

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
      case GET -> Root / "cluster" => Ok(clusterOverseer.getClusterState())
      // Cluster admin
      case req @ POST -> Root / "cluster" / "addWorker" =>
        Ok(for {
          workerCfg <- req.as[WorkerConfig]
          res       <- clusterOverseer.addWorker(workerCfg)
        } yield res)
      case DELETE -> Root / "cluster" / "removeWorker" / IntVar(workerId) => Ok(clusterOverseer.removeWorker(workerId))
    }
  }

  def routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes()
  )

}
