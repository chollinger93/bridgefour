package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import cats.effect.Sync
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class WorkerCacheSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  test("WorkerCache seeds cache") {
    for {
      cfg    <- Config.load[IO]()
      wState <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache <- WorkerCache.makeF[IO](cfg, wState)
      values <- wCache.list()
      _       = assertEquals(values.keys, Set(0))
    } yield ()
  }

}
