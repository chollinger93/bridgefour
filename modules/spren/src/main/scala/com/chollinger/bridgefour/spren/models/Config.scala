package com.chollinger.bridgefour.spren.models

import cats.effect.Sync
import cats.effect.unsafe.implicits._
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import pureconfig._
import pureconfig.generic.derivation.default._
import pureconfig.module.catseffect.syntax._

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
