package com.chollinger.bridgefour.rock.http

import cats.Monad
import cats.effect.kernel.Sync
import cats.effect.Async
import cats.effect._
import cats.syntax.all.*
import com.chollinger.bridgefour.rock.services.WorkerService
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.Worker
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.comcast.ip4s.*
import fs2.io.net.Network
import io.circe._
import io.circe.generic.auto._
import io.circe.literal._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.io._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.middleware.Logger

case class WorkerRoutes[F[_]: Monad](workerSrv: WorkerService[F]) extends Http4sDsl[F] with Route[F] {

  protected val prefixPath: String = "/worker"

  protected def httpRoutes(): HttpRoutes[F] = {
    HttpRoutes.of[F] {
      case GET -> Root / "status" => Ok()
      case GET -> Root / "state" =>
        workerSrv.state().flatMap { (state: WorkerState) =>
          Ok(state.asJson)
        }
    }
  }

  def routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes()
  )

}
