package com.chollinger.bridgefour.kaladin.services

import java.io.File
import java.nio.file.Files

import scala.collection.immutable.List
import scala.concurrent.duration.DurationDouble
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.data.Kleisli
import cats.effect.*
import cats.effect.kernel.Fiber
import cats.effect.std.UUIDGen
import cats.implicits.*
import cats.syntax.all.toTraverseOps
import cats.syntax.all._
import cats.syntax.traverse.toTraverseOps
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.kaladin.TestUtils.Http.*
import com.chollinger.bridgefour.kaladin.TestUtils.*
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.jobs.*
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.comcast.ip4s.*
import fs2.io.net.Network
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
class WorkerOverseerSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  given EntityDecoder[IO, WorkerState]                                = accumulatingJsonOf[IO, WorkerState]

  test("WorkerOverseerService.getWorkerState reports valid state") {
    for {
      cfg <- Config.load[IO]() // TODO: fixtures
      srv  = WorkerOverseerService.make[IO](cfg, mockClient(halfUsedWorkerState))
      _   <- assertIO(srv.getWorkerState(cfg.workers.head), halfUsedWorkerState)
    } yield ()
  }

  test("WorkerOverseerService.listAvailableWorkers lists valid workers") {
    for {
      cfg     <- Config.load[IO]()
      srv      = WorkerOverseerService.make[IO](cfg, mockClient(halfUsedWorkerState))
      workers <- srv.listAvailableWorkers()
      // The config only has one worker
      _ = assertEquals(workers.map(_.availableSlots.size).head, 1)
      _ = assertEquals(workers.map(_.slots.size).head, 2)
//      _ = assertEquals(workers.map(_.runningTasks.size).head, 1)
      _ = assertEquals(workers.size, 1)
      _ = assertEquals(workers, List(halfUsedWorkerState))
    } yield ()
  }

}
