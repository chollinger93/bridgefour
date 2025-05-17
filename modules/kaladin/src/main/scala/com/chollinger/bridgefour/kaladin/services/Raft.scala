package com.chollinger.bridgefour.kaladin.services

import cats.effect.Clock
import cats.effect.Concurrent
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.kernel.Temporal
import cats.effect.std.AtomicCell
import cats.effect.std.Mutex
import cats.implicits.*
import com.chollinger.bridgefour.shared.extensions.CalledLocked
import com.chollinger.bridgefour.shared.extensions.FullyLocked
import com.chollinger.bridgefour.shared.extensions.PartiallyLocked
import com.chollinger.bridgefour.shared.models.Config.LeaderConfig
import com.chollinger.bridgefour.shared.models.Config.RaftConfig
import com.chollinger.bridgefour.shared.models.RaftState.Candidate
import com.chollinger.bridgefour.shared.models.RaftState.Follower
import com.chollinger.bridgefour.shared.models.RaftState.Leader
import com.chollinger.bridgefour.shared.models.HeartbeatRequest
import com.chollinger.bridgefour.shared.models.RaftState
import com.chollinger.bridgefour.shared.models.RequestVote
import com.chollinger.bridgefour.shared.models.RequestVoteResponse
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.EntityDecoder
import org.http4s.EntityEncoder
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.Uri
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

trait RaftService[F[_]] {

  def runFibers(): F[Unit]

  // Handles a vote request from other Candidates
  def handleVote(req: RequestVote): F[RequestVoteResponse]

  // Handle a heartbeat from the leader; this is used to reset the election timer
  def handleHeartbeat(req: HeartbeatRequest): F[Unit]

// Returns the current view of the world
  def getState: F[RaftElectionState]

}

case class RaftElectionState(
    ownId: Int,
    term: Int = 0,
    ownState: RaftState = Follower,
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

  // TODO: distributed log
  // TODO: dynamic list of peers
  // TODO: state should be done on non-volatile storage
  def make[F[_]: ThrowableMonadError: Async: Clock: Logger: Concurrent](
      client: Client[F],
      lock: Mutex[F],
      state: AtomicCell[F, RaftElectionState],
      cfg: RaftConfig = RaftConfig()
  ): RaftService[F] =
    new RaftService[F] {
      given EntityDecoder[F, RequestVote]         = accumulatingJsonOf[F, RequestVote]
      given EntityEncoder[F, RequestVote]         = jsonEncoderOf[F, RequestVote]
      given EntityEncoder[F, HeartbeatRequest]    = jsonEncoderOf[F, HeartbeatRequest]
      given EntityEncoder[F, RequestVoteResponse] = jsonEncoderOf[F, RequestVoteResponse]
      given EntityDecoder[F, RequestVoteResponse] = accumulatingJsonOf[F, RequestVoteResponse]

      given logger: Logger[F]                 = Slf4jLogger.getLogger[F]
      private val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]

      override def runFibers(): F[Unit] =
        for {
          _ <- Logger[F].debug("Starting raft election handler")
          _ <- sendHeartbeatLoop().start
          _ <- bgThread().start
          _ <- Async[F].never
        } yield ()

      // Handles elections and heartbeats, recursively
      private def bgThread(): F[Unit] = {
        val nextDelay = Temporal[F].realTime.map { now =>
          val jitter = scala.util.Random.between(cfg.minTimeout.toMillis, cfg.maxTimeout.toMillis).millis
          now -> jitter
        }
        for {
          (now, delay) <- nextDelay
          _            <- Logger[F].debug(s"Election timer: $now, delay: $delay")
          _            <- Temporal[F].sleep(delay)
          s            <- state.get
          lastHb        = s.lastHeartbeatEpoch.getOrElse(0L)
          lastElection  = s.lastElectionEpoch.getOrElse(0L)
          _ <- s.ownState match {
                 case Leader => sendHeartbeatToFollowers(now)
                 case _      => prepElection(now, delay)
               }
          // Recursive
          _ <- bgThread()
        } yield ()
      }

      private def sendHeartbeatLoop(): F[Unit] = {

        for {
          now <- Temporal[F].realTime
          s   <- state.get
          _ <- s.ownState match {
                 case Leader => sendHeartbeatToFollowers(now)
                 case _      => Async[F].unit
               }
          _ <- Temporal[F].sleep(cfg.heartbeatInterval)
          _ <- sendHeartbeatLoop()
        } yield ()
      }

      @FullyLocked
      private def sendHeartbeatToFollowers(now: FiniteDuration): F[Unit] = lock.lock.surround {
        for {
          s <- state.get
          hb = HeartbeatRequest(
                 term = s.term,
                 currentLeader = s.ownId,
                 ts = now.toMillis
               )
          _ <- s.peers.parTraverse { fCfg =>
                 val req =
                   Request[F](method = Method.POST, uri = Uri.unsafeFromString(s"${fCfg.uri()}/raft/heartbeat"))
                     .withEntity(hb)
                 Logger[F].debug(s"Sending heartbeat to $fCfg") >>
                   err.handleErrorWith(
                     client.status(req).flatMap {
                       case Status.Ok => Async[F].unit
                       case s         => err.raiseError(new Exception(s"Failed to send heartbeat to $fCfg, status: $s"))
                     }
                   )(e =>
                     Logger[F].error(e)(s"Sending heartbeat to follower ${fCfg.id} failed with $e") >>
                       Async[F].unit
                   )
               }
        } yield ()
      }

      @FullyLocked
      private def prepElection(now: FiniteDuration, delay: FiniteDuration): F[Unit] = {
        lock.lock.surround {
          for {
            snapshot <- state.get
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
          } yield ()
        }
      }

      @CalledLocked
      private def startElection: F[Unit] = {
        for {
          now <- Temporal[F].realTime
          _   <- Logger[F].info("Starting election")
          // We're now a candidate for the new term - this is the only time the term gets updated
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
                       Logger[F].error(e)(s"Election on leader ${lCfg.id} failed") >>
                         Async[F].blocking(
                           RequestVoteResponse(
                             term = s.term,
                             voteGranted = false
                           )
                         )
                     )
                 }
          _ <- Logger[F].debug(s"Received votes: $res")
          // One vote for ourselves
          _ <- res.map(_.voteGranted).count(_ == true) + 1 match {
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

      @PartiallyLocked
      override def handleVote(req: RequestVote): F[RequestVoteResponse] = {
        for {
          _  <- logger.info(s"Received vote request from ${req.candidateId} for term ${req.term}")
          s  <- state.get
          ts <- Temporal[F].realTime

          // Update state in case we became a follower
          s <- if (req.term > s.term) {
                 logger.info(
                   s"Candidate ${req.candidateId} has a higher term than us (${req.term}/${s.term}, accepting leader"
                 )
                   >> state.update(
                     _.copy(
                       ownState = Follower, lastElectionEpoch = Some(ts.toMillis), votedFor = None,
                       currentLeader = Some(req.candidateId), term = req.term
                     )
                   ) >> state.get
                 // No state change
               } else Async[F].blocking(s)
          votedForCandidate = s.votedFor
          ourTerm           = s.term
          voteGranted       = req.term == ourTerm && (votedForCandidate.isEmpty || votedForCandidate.get == req.candidateId)
          _ <-
            logger.info(s"Our vote for ${req.candidateId}: $voteGranted, ourTerm: $ourTerm, theirTerm: ${req.term}, votedForCandidate: $votedForCandidate")
          term <- if (voteGranted) {
                    lock.lock.surround {
                      state.update(
                        _.copy(
                          ownState = Follower, lastHeartbeatEpoch = Some(ts.toMillis),
                          lastElectionEpoch = Some(ts.toMillis), votedFor = Some(req.candidateId),
                          currentLeader = Some(req.candidateId), term = req.term
                        )
                      )
                    }
                      >> Async[F].blocking(req.term)
                  } else Async[F].blocking(ourTerm)
          s <- state.get
          _ <- logger.info(s"State after vote: $s")
        } yield RequestVoteResponse(
          term = term,
          voteGranted = voteGranted
        )
      }

      @FullyLocked
      override def handleHeartbeat(req: HeartbeatRequest): F[Unit] = {
        lock.lock.surround {
          for {
            s <- state.get
            _ <- if (req.term > s.term || (req.term == s.term && s.ownState != Leader)) {
                   state.update(
                     _.copy(
                       term = req.term,
                       ownState = Follower,
                       lastHeartbeatEpoch = Some(req.ts),
                       currentLeader = Some(req.currentLeader)
                     )
                   )
                 } else Async[F].unit
          } yield ()
        }
      }

      override def getState: F[RaftElectionState] = state.get
    }

}
