package com.chollinger.bridgefour.spren.http

import cats.Monad
import cats.effect.*
import cats.effect.kernel.Sync
import cats.syntax.all.*
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.Worker
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.spren.services.WorkerService
import com.comcast.ip4s.*
import fs2.io.net.Network
import io.circe.*
import io.circe.generic.auto.*
import io.circe.literal.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.Logger

case class WorkerRoutes[F[_]: Monad](workerSrv: WorkerService[F]) extends Http4sDsl[F] with Route[F] {

  protected val prefixPath: String = "/worker"

  protected def httpRoutes(): HttpRoutes[F] = {
    HttpRoutes.of[F] { case GET -> Root / "state" =>
      workerSrv.state().flatMap { (state: WorkerState) =>
        Ok(state.asJson)
      }
    }
  }

  def routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes()
  )

}
