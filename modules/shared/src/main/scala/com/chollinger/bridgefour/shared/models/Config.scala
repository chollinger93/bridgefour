package com.chollinger.bridgefour.shared.models

import scala.concurrent.duration.DurationDouble
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.Uri
import pureconfig.*
import pureconfig.generic.derivation.default.*

object Config {

  trait HostnameConfig {

    def schema: String

    def host: String

    def port: Int

    // TODO: unsafe
    def uri(): Uri = Uri.fromString(s"$schema://$host:$port").toOption.get

  }

  case class LeaderConfig(
      id: Int,
      schema: String,
      host: String,
      port: Int = 5555
  ) extends HostnameConfig derives ConfigReader, Encoder.AsObject, Decoder

  case class WorkerConfig(
      id: WorkerId,
      schema: String,
      host: String,
      port: Int = 5554
  ) extends HostnameConfig derives ConfigReader, Encoder.AsObject, Decoder

  case class SprenConfig(
      id: WorkerId,
      schema: String,
      host: String,
      port: Int = 5554,
      maxSlots: Int = 2,
      probingTimeout: FiniteDuration = 0.2.seconds
  ) extends HostnameConfig derives ConfigReader

}
