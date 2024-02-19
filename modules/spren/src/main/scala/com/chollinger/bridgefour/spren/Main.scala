package com.chollinger.bridgefour.spren

import cats.effect.{IO, IOApp}
import com.chollinger.bridgefour.spren.http.Server
import com.chollinger.bridgefour.spren.models.Config
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

object Main extends IOApp.Simple {

  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  def run: IO[Unit] =
    for {
      cfg <- Config.load[IO]()
      _   <- Logger[IO].info(s"Summoning a spren at ${cfg.self.uri()} with id ${cfg.self.id}...")
      _   <- Server.run[IO](cfg)
    } yield ()

}
