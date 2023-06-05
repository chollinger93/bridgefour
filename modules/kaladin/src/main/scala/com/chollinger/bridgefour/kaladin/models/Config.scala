package com.chollinger.bridgefour.kaladin.models

import cats.effect.unsafe.implicits.*
import cats.effect.IO
import cats.effect.Sync
import com.chollinger.bridgefour.shared.models.Config.*
import org.http4s.Uri
import pureconfig.*
import pureconfig.generic.derivation.default.*
import pureconfig.module.catseffect.syntax.*
object Config {

  case class ServiceConfig(
      self: LeaderConfig,
      workers: List[WorkerConfig]
  ) derives ConfigReader

  def load[F[_]: Sync](): F[ServiceConfig] = ConfigSource.default.loadF[F, ServiceConfig]()

}
