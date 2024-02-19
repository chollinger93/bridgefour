package com.chollinger.bridgefour.kaladin

import cats.effect.IO
import cats.effect.IOApp
import com.chollinger.bridgefour.kaladin.http.Server
import com.chollinger.bridgefour.kaladin.models.Config
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple {

  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  def run: IO[Unit] =
    for {
      cfg <- Config.load[IO]()
      _   <- Logger[IO].info(s"Summoning bridge boy at ${cfg.self.uri()}")
      _   <- Server.run[IO](cfg)
    } yield ()

}
