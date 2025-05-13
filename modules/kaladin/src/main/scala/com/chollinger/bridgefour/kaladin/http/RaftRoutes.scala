package com.chollinger.bridgefour.kaladin.http

import cats.effect.Concurrent
import cats.syntax.all.*
import com.chollinger.bridgefour.kaladin.programs.JobController
import com.chollinger.bridgefour.kaladin.services.ClusterOverseer
import com.chollinger.bridgefour.kaladin.services.RaftService
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.JobDetails
import com.chollinger.bridgefour.shared.models.Job.UserJobConfig
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.models.Job
import com.chollinger.bridgefour.shared.models.RequestVote
import com.chollinger.bridgefour.shared.models.RequestVoteResponse
import com.chollinger.bridgefour.shared.models.WorkerStatus
import io.circe.Json
import io.circe.disjunctionCodecs.encodeEither
import org.http4s.*
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

case class RaftRoutes[F[_]: Concurrent](svc: RaftService[F]) extends Http4sDsl[F] with Route[F] {

  protected val prefixPath: String = "/raft/"

  given EntityDecoder[F, RequestVote] = accumulatingJsonOf[F, RequestVote]

  given EntityDecoder[F, Long] = accumulatingJsonOf[F, Long]

  given EntityEncoder[F, RequestVoteResponse] = jsonEncoderOf[F, RequestVoteResponse]

  protected def httpRoutes(): HttpRoutes[F] = {
    HttpRoutes.of[F] {
      case req @ POST -> Root / "requestVote" =>
        Ok(for {
          body <- req.as[RequestVote]
          resp <- svc.handleVote(body)
        } yield resp)

      // Receive a heartbeat from the leader
      case req @ POST -> Root / "heartbeat" =>
        Ok(for {
          ts   <- req.as[Long]
          resp <- svc.handleHeartbeat(ts)
        } yield resp)

    }
  }

  def routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes()
  )

}
