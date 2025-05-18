package com.chollinger.bridgefour.kaladin.http

import cats.effect.Concurrent
import cats.syntax.all._
import com.chollinger.bridgefour.kaladin.services.RaftService
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.HeartbeatRequest
import com.chollinger.bridgefour.shared.models.RaftEncoders
import com.chollinger.bridgefour.shared.models.RequestVote
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

case class RaftRoutes[F[_]: Concurrent](svc: RaftService[F]) extends Http4sDsl[F] with Route[F] with RaftEncoders[F] {

  protected def httpRoutes(): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req @ POST -> Root / "requestVote" =>
        Ok(for {
          body <- req.as[RequestVote]
          resp <- svc.handleVote(body)
        } yield resp)

      // Receive a heartbeat from the leader
      case req @ POST -> Root / "heartbeat" =>
        Ok(for {
          r    <- req.as[HeartbeatRequest]
          resp <- svc.handleHeartbeat(r)
        } yield resp)
      // Get state
      case GET -> Root / "state" =>
        Ok(svc.getState)
    }

  def routes: HttpRoutes[F] = Router(
    "raft" -> httpRoutes()
  )

}
