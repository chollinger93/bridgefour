package com.chollinger.bridgefour.spren.http

import cats.Monad
import cats.syntax.all._
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.Worker
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.spren.services.WorkerService
import io.circe._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.io._
import org.http4s.server.Router

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
