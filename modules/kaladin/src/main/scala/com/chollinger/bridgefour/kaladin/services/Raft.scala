package com.chollinger.bridgefour.kaladin.services

import cats.effect.implicits.*
import cats.effect.Clock
import cats.effect.Concurrent
import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.kernel.Temporal
import cats.effect.std.AtomicCell
import cats.effect.std.Mutex
import com.chollinger.bridgefour.shared.models.RaftState
import com.chollinger.bridgefour.shared.models.RequestVote
import com.chollinger.bridgefour.shared.models.RequestVoteResponse
import cats.implicits.*
import com.chollinger.bridgefour.shared.models.Config.LeaderConfig
import com.chollinger.bridgefour.shared.models.IDs.TaskId
import com.chollinger.bridgefour.shared.models.RaftState.Candidate
import com.chollinger.bridgefour.shared.models.RaftState.Follower
import com.chollinger.bridgefour.shared.models.RaftState.Leader
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.EntityDecoder
import org.http4s.EntityEncoder
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationLong
import java.util.concurrent.TimeUnit

trait RaftService[F[_]] {

  def runElectionTimer(): F[Unit]

  def handleVote(req: RequestVote): F[RequestVoteResponse]

  // Handle a heartbeat from the leader; this is used to reset the election timer
  def handleHeartbeat(ts: Long): F[Unit]

  def getState: F[RaftElectionState]

}

case class RaftElectionState(
    ownId: Int,
    ownState: RaftState = Follower,
    term: Int = 0,
    votedFor: Option[Int] = None,
    lastElectionEpoch: Option[Long] = None,
    lastHeartbeatEpoch: Option[Long] = None,
    currentLeader: Option[Int] = None,
    peers: List[LeaderConfig] = List.empty
) derives Encoder.AsObject,
      Decoder

object RaftElectionState {

  def apply(ownId: Int, peers: List[LeaderConfig]): RaftElectionState =
    RaftElectionState(ownId = ownId).copy(peers = peers)

}

object RaftService {

  // TODO: election to peers
  // TODO: Mutex
  // TODO: dynamic list of peers
  def make[F[_]: ThrowableMonadError: Async: Clock: Logger: Concurrent](
      client: Client[F],
      lock: Mutex[F],
      state: AtomicCell[F, RaftElectionState]
  ): RaftService[F] =
    new RaftService[F] {
      given EntityDecoder[F, RequestVote]         = accumulatingJsonOf[F, RequestVote]
      given EntityEncoder[F, RequestVote]         = jsonEncoderOf[F, RequestVote]
      given EntityEncoder[F, RequestVoteResponse] = jsonEncoderOf[F, RequestVoteResponse]
      given EntityDecoder[F, RequestVoteResponse] = accumulatingJsonOf[F, RequestVoteResponse]

      given logger: Logger[F]                 = Slf4jLogger.getLogger[F]
      private val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]
      private val minTimeout                  = 1500L.millis
      private val maxTimeout                  = 3000L.millis

      override def runElectionTimer(): F[Unit] =
        for {
          _ <- Logger[F].debug("Starting raft election handler")
          _ <- bgThread() // runs recursively, no need for foreverM
        } yield ()

      private def startElection: F[Unit] = {
        for {
          now <- Temporal[F].realTime
          _   <- Logger[F].info("Starting election")
          // We're now a candidate for the new term
          _ <- state.update { s =>
                 val newTerm = s.term + 1
                 s.copy(
                   ownState = Candidate,
                   term = newTerm,
                   votedFor = Some(s.ownId),
                   lastElectionEpoch = Some(now.toMillis)
                 )
               }
          s <- state.get
          voteReq = RequestVote(
                      term = s.term,
                      candidateId = s.ownId
                    )
          _ <- Logger[F].info(s"Requesting vote $voteReq from peers: ${s.peers}")
          res <- s.peers.parTraverse { lCfg =>
                   val req =
                     Request[F](method = Method.POST, uri = Uri.unsafeFromString(s"${lCfg.uri()}/raft/requestVote"))
                       .withEntity(voteReq)
                   Logger[F].info(s"Requesting election on $lCfg") >> err
                     .handleErrorWith(client.expect[RequestVoteResponse](req))(e =>
                       Logger[F].error(e)(s"Starting election on leader ${lCfg.id} failed") >>
                         Async[F].blocking(
                           RequestVoteResponse(
                             term = s.term,
                             voteGranted = false
                           )
                         )
                     )
                 }
          _ <- Logger[F].debug(s"Received votes: $res")
          _ <- res.map(_.voteGranted).count(_ == true) match {
                 case count if count > s.peers.size / 2 =>
                   Logger[F].info(s"Received majority of votes, becoming leader") >>
                     state.update(_.copy(ownState = Leader, currentLeader = Some(s.ownId)))
                 case _ =>
                   Logger[F].info(s"Did not receive majority of votes, remaining candidate")
               }
          s <- state.get
          _ <- Logger[F].debug(s"State after vote: $s")
        } yield ()
      }

      override def handleVote(req: RequestVote): F[RequestVoteResponse] = {
        for {
          _                 <- logger.info(s"Received vote request from ${req.candidateId} for term ${req.term}")
          ourTerm           <- getTerm
          ts                <- Temporal[F].realTime
          votedForCandidate <- getVotedFor
          voteGranted        = req.term >= ourTerm && (votedForCandidate.isEmpty || votedForCandidate.get == req.candidateId)
          _                 <- logger.info(s"Our vote: $voteGranted, ourTerm: $ourTerm, votedForCandidate: $votedForCandidate")
          term <- if (voteGranted) {
                    setTerm(req.term)
                      >> state.update(
                        _.copy(
                          ownState = Follower,
                          lastHeartbeatEpoch = Some(ts.toMillis),
                          votedFor = Some(req.candidateId),
                          currentLeader = Some(req.candidateId)
                        )
                      )
                      >> Async[F].blocking(req.term)
                  } else Async[F].blocking(ourTerm)
          s <- state.get
          _ <- logger.info(s"State after vote: $s")
        } yield RequestVoteResponse(
          term = term,
          voteGranted = voteGranted
        )
      }

      override def handleHeartbeat(ts: Long): F[Unit] = {
        lock.lock.surround {
          for {
            _        <- logger.debug(s"Trying to set heartbeat to $ts")
            ourState <- state.get.map(_.ownState)
            _ <- if (ourState != Leader) setLastHeartbeatTimestamp(ts) >> logger.debug(s"Setting heartbeat to $ts")
                 else Async[F].unit
          } yield ()
        }
      }

      private def bgThread(): F[Unit] = {
        lock.lock.surround {
          val nextDelay = Temporal[F].realTime.map { now =>
            val jitter = scala.util.Random.between(minTimeout.toMillis, maxTimeout.toMillis).millis
            now -> jitter
          }
          for {
            (now, delay) <- nextDelay
            _            <- Temporal[F].sleep(delay)
            snapshot     <- state.get
            hasPeers <- if (snapshot.peers.isEmpty) {
                          Async[F].blocking(false)
                        } else Async[F].blocking(true)
            lastHb       = snapshot.lastHeartbeatEpoch.getOrElse(0L)
            lastElection = snapshot.lastElectionEpoch.getOrElse(0L)
            timeSinceHb  = now.toMillis - lastHb
            timeSinceEl  = now.toMillis - lastElection
            timeoutMs    = delay.toMillis

            _ <- if (
                   hasPeers &&
                   snapshot.ownState != Leader &&
                   timeSinceHb >= timeoutMs &&
                   timeSinceEl >= timeoutMs
                 ) {
                   Logger[F].info(
                     s"Election timeout reached, starting election, timeSinceHeartbeat: $timeSinceHb, timeSinceElection: $timeSinceEl"
                   ) >>
                     startElection
                 } else if (!hasPeers) {
                   logger.warn("No peers, skipping election timer. It's lonely at the top.")
                 } else {
                   Logger[F].debug(s"Election timer skipped: heartbeat or election recently occurred: timeSinceHeartbeat: $timeSinceHb, timeSinceElection: $timeSinceEl")
                 }
            // Recursive
            _ <- bgThread()
          } yield ()
        }
      }

      override def getState: F[RaftElectionState] = state.get

      private def getOwnState: F[RaftState]                    = state.get.map(_.ownState)
      private def setOwnState(st: RaftState): F[Unit]          = state.update(_.copy(ownState = st))
      private def getTerm: F[Int]                              = state.get.map(_.term)
      private def setTerm(term: Int): F[Unit]                  = state.update(_.copy(term = term))
      private def getVotedFor: F[Option[Int]]                  = state.get.map(_.votedFor)
      private def setLastHeartbeatTimestamp(ts: Long): F[Unit] = state.update(_.copy(lastHeartbeatEpoch = Some(ts)))
    }

}
