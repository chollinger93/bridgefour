package com.chollinger.bridgefour.shared.http

import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

trait Route[F[_]] {
  protected def prefixPath: String
  protected def httpRoutes(): HttpRoutes[F]
  def routes: HttpRoutes[F]
}
