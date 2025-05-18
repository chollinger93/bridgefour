package com.chollinger.bridgefour.kaladin.services

import cats.effect.*
import cats.effect.kernel.Resource
import cats.effect.std.AtomicCell
import cats.effect.std.Mutex
import cats.implicits.*
import com.chollinger.bridgefour.kaladin.http.RaftRoutes
import com.chollinger.bridgefour.shared.models.Config.LeaderConfig
import com.chollinger.bridgefour.shared.models.Config.RaftConfig
import com.chollinger.bridgefour.shared.models.RaftElectionState
import com.chollinger.bridgefour.shared.models.RaftState.Follower
import com.chollinger.bridgefour.shared.models.RaftState.Leader
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger as Http4sLogger
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.collection.immutable.List
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

class RaftServiceIntegrationTest extends CatsEffectSuite {

  given logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  private val testTimeout: FiniteDuration = 5.seconds

  private def raftNodeResource(l: LeaderConfig, leaders: List[LeaderConfig], cfg: RaftConfig): Resource[IO, Unit] =
    for {
      client   <- EmberClientBuilder.default[IO].build
      raftLock <- Resource.eval(Mutex[IO])
      raftState <- Resource.eval(
                     AtomicCell[IO].of(
                       RaftElectionState(
                         ownId = l.id,
                         peers = leaders.filter(_.id != l.id)
                       )
                     )
                   )
      raftSvc = RaftService.make[IO](client, raftLock, raftState, cfg)
      httpApp = RaftRoutes[IO](raftSvc).routes.orNotFound
      _ <- EmberServerBuilder
             .default[IO]
             .withHost(Host.fromString(l.host).get)
             .withPort(Port.fromInt(l.port).get)
             .withHttpApp(Http4sLogger.httpApp(false, false)(httpApp))
             .build
      _ <- Resource.make(raftSvc.runFibers())(_ => IO.unit)
    } yield ()

  private def testState(client: Client[IO], leaders: List[LeaderConfig]): IO[Unit] = {

    for {
      _ <- IO.sleep(1.second)
      resps <- leaders.traverse { l =>
                 val uri = Uri.unsafeFromString(s"http://${l.host}:${l.port}/raft/state")
                 val req = Request[IO](method = Method.GET, uri = uri)
                 logger.info(s"Testing state for ${l.id}") >> client.expect[RaftElectionState](req)
               }
      _ <- logger.info(s"Got response: $resps")
      // Exactly one leader
      _ = assertEquals(
            resps.count(_.ownState == Leader),
            1
          )
      // 2 Followers
      _ = assertEquals(
            resps.count(_.ownState == Follower),
            2
          )
    } yield ()
  }

  test("Rafts elect a leader in a reasonable timeframe") {
    val leaders = List(
      LeaderConfig(1, "http", "localhost", 5555),
      LeaderConfig(2, "http", "localhost", 5556),
      LeaderConfig(3, "http", "localhost", 5557)
    )

    val cfg = RaftConfig(
      minTimeout = 100.millis,
      maxTimeout = 200.millis,
      heartbeatInterval = 50.millis
    )
    val prog: Resource[IO, Unit] = for {
      _ <- leaders.parTraverse(raftNodeResource(_, leaders, cfg))
    } yield ()

    for {
      _     <- prog.useForever.start
      _     <- IO.sleep(testTimeout)
      client = EmberClientBuilder.default[IO].build
      _ <- {
        client.use { client =>
          testState(client, leaders)
        }
      }
    } yield ()
  }

}
