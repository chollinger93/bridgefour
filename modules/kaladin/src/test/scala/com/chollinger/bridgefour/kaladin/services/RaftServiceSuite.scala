package com.chollinger.bridgefour.kaladin.services

import cats.effect.*
import cats.effect.kernel.Resource
import cats.effect.std.AtomicCell
import cats.effect.std.Mutex
import com.chollinger.bridgefour.shared.models.Config.LeaderConfig
import com.chollinger.bridgefour.shared.models.Config.RaftConfig
import com.chollinger.bridgefour.shared.models.HeartbeatRequest
import com.chollinger.bridgefour.shared.models.RaftElectionState
import com.chollinger.bridgefour.shared.models.RaftState.Follower
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.collection.immutable.List
import scala.concurrent.duration.DurationLong
import scala.language.postfixOps

class RaftServiceSuite extends CatsEffectSuite {

  given logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  private val l = LeaderConfig(1, "http", "localhost", 5555)
  private val leaders = List(
    LeaderConfig(2, "http", "localhost", 5556),
    LeaderConfig(3, "http", "localhost", 5557)
  )

  def testUnsafe(test: RaftService[IO] => IO[Unit]): Unit = {
    val cfg = RaftConfig(
      minTimeout = 100.millis,
      maxTimeout = 200.millis,
      heartbeatInterval = 50.millis
    )
    val prog = for {
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
      prog   <- Resource.eval(test(raftSvc))
    } yield prog
    prog.use(_ => IO.unit).unsafeRunSync()
  }

  private def testStartingState(state: RaftElectionState): Unit = {
    assertEquals(state.ownId, l.id)
    assertEquals(state.ownState, Follower)
    assertEquals(state.currentLeader, None)
    assertEquals(state.term, 0)
    assertEquals(state.votedFor, None)
    assertEquals(state.lastElectionEpoch, None)
    assertEquals(state.lastHeartbeatEpoch, None)
  }

  test("Node accept heartbeat") {
    testUnsafe((raftSvc: RaftService[IO]) =>
      for {
        state <- raftSvc.getState
        _      = testStartingState(state)
        _ <- raftSvc.handleHeartbeat(
               HeartbeatRequest(
                 term = 1,
                 currentLeader = l.id,
                 ts = System.currentTimeMillis()
               )
             )
        state <- raftSvc.getState
        _     <- logger.info(s"State after heartbeat: $state")
        _      = assertEquals(state.ownState, Follower)
        _      = assertEquals(state.currentLeader, Some(l.id))
        _      = assertEquals(state.term, 1)
      } yield ()
    )
  }

  test("Node rejects invalid heartbeat") {
    testUnsafe((raftSvc: RaftService[IO]) =>
      for {
        state <- raftSvc.getState
        _      = testStartingState(state)
        _ <- raftSvc.handleHeartbeat(
               HeartbeatRequest(
                 term = -1,
                 currentLeader = l.id,
                 ts = System.currentTimeMillis()
               )
             )
        state <- raftSvc.getState
        _     <- logger.info(s"State after heartbeat: $state")
        // Same as before
        _ = testStartingState(state)
      } yield ()
    )
  }

}
