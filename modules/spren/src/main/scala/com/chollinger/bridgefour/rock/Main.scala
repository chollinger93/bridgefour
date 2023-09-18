package com.chollinger.bridgefour.rock

import cats.effect.IO
import cats.effect.IOApp
import com.chollinger.bridgefour.rock.http.Server
import com.chollinger.bridgefour.rock.models.Config
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger

object Main extends IOApp.Simple {

  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  def run: IO[Unit] =
    for {
      cfg <- Config.load[IO]()
      _   <- Logger[IO].info(s"Summoning a spren at ${cfg.self.uri()}")
      _   <- Server.run[IO](cfg)
    } yield ()

}
