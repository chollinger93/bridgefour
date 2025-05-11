package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import cats.effect.Sync
import cats.implicits.*
import com.chollinger.bridgefour.kaladin.Jobs
import com.chollinger.bridgefour.kaladin.TestUtils.Http.*
import com.chollinger.bridgefour.kaladin.TestUtils.MockIDMaker
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpDir
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpFile
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.JobId
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Task.*
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
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
