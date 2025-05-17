package com.chollinger.bridgefour.kaladin.models

import cats.effect.Sync
import com.chollinger.bridgefour.shared.models.Config._
import com.chollinger.bridgefour.shared.models.IDs.ClusterId
import pureconfig._
import pureconfig.generic.derivation.default._
import pureconfig.module.catseffect.syntax._
object Config {

  case class ServiceConfig(
      self: LeaderConfig,
      leaders: List[LeaderConfig],
      workers: List[WorkerConfig],
      clusterId: ClusterId = 0,
      raft: RaftConfig = RaftConfig()
  ) derives ConfigReader

  def load[F[_]: Sync](): F[ServiceConfig] = ConfigSource.default.loadF[F, ServiceConfig]()

}
