package com.chollinger.bridgefour.shared.http

import org.http4s._

trait Route[F[_]] {

  def routes: HttpRoutes[F]

}
