package com.chollinger.bridgefour.shared.models

import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.*
import pureconfig.generic.derivation.default.*
import cats.effect.IO
import cats.effect.Sync
import cats.effect.unsafe.implicits.*
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.Uri
import pureconfig.module.catseffect.syntax.*
import pureconfig.generic.derivation.default.*
import pureconfig.generic.derivation.ConfigReaderDerivation

import concurrent.duration.DurationDouble
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

object Config {

  trait HostnameConfig {

    def schema: String

    def host: String

    def port: Int

    // TODO: unsafe
    def uri(): Uri = Uri.fromString(s"$schema://$host:$port").toOption.get

  }

  case class LeaderConfig(
      schema: String,
      host: String,
      port: Int = 5555
  ) extends HostnameConfig
      derives ConfigReader

  case class WorkerConfig(
      id: WorkerId,
      schema: String,
      host: String,
      port: Int = 5554
  ) extends HostnameConfig
      derives ConfigReader

  case class SprenConfig(
      id: WorkerId,
      schema: String,
      host: String,
      port: Int = 5554,
      maxSlots: Int = 2,
      probingTimeout: FiniteDuration = 0.2 seconds
  ) extends HostnameConfig
      derives ConfigReader

}
