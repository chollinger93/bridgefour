package com.chollinger.bridgefour.spren.models

import cats.effect.unsafe.implicits.*
import cats.effect.IO
import cats.effect.Sync
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import pureconfig.*
import pureconfig.generic.derivation.default.*
import pureconfig.module.catseffect.syntax.*

object Config {

  case class ServiceConfig(
      self: SprenConfig,
      leader: LeaderConfig
  ) derives ConfigReader

  case class LeaderConfig(
      host: String,
      port: Int = 5555
  ) derives ConfigReader

  def load[F[_]: Sync](): F[ServiceConfig] = ConfigSource.default.loadF[F, ServiceConfig]()

}
