package com.chollinger.bridgefour.shared.models

import scala.concurrent.duration.DurationDouble
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import org.http4s.Uri
import pureconfig._
import pureconfig.generic.derivation.default._

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
  ) extends HostnameConfig derives ConfigReader

  case class WorkerConfig(
      id: WorkerId,
      schema: String,
      host: String,
      port: Int = 5554
  ) extends HostnameConfig derives ConfigReader

  case class SprenConfig(
      id: WorkerId,
      schema: String,
      host: String,
      port: Int = 5554,
      maxSlots: Int = 2,
      probingTimeout: FiniteDuration = 0.2.seconds
  ) extends HostnameConfig derives ConfigReader

}
