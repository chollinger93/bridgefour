package com.chollinger.bridgefour.shared.http

import org.http4s._

trait Route[F[_]] {

  protected def prefixPath: String
  protected def httpRoutes(): HttpRoutes[F]
  def routes: HttpRoutes[F]

}
