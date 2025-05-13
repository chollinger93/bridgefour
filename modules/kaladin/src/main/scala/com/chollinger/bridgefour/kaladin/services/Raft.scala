package com.chollinger.bridgefour.kaladin.services

import cats.effect.Clock
import cats.effect.Ref
import cats.effect.kernel.Async
import cats.effect.kernel.Temporal
import cats.effect.std.AtomicCell
import com.chollinger.bridgefour.shared.models.RaftState
import com.chollinger.bridgefour.shared.models.RequestVote
import com.chollinger.bridgefour.shared.models.RequestVoteResponse
import cats.implicits.*
import com.chollinger.bridgefour.shared.models.Config.LeaderConfig
import com.chollinger.bridgefour.shared.models.RaftState.Candidate
import com.chollinger.bridgefour.shared.models.RaftState.Follower
import com.chollinger.bridgefour.shared.models.RaftState.Leader
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationLong
import java.util.concurrent.TimeUnit

trait RaftService[F[_]] {

  def runElectionTimer(): F[Unit]

  def handleVote(req: RequestVote): F[RequestVoteResponse]

  // Handle a heartbeat from the leader; this is used to reset the election timer
  def handleHeartbeat(ts: Long): F[Unit]

}

case class RaftElectionState(
    ownState: RaftState = Follower,
    term: Int = 0,
    votedFor: Option[Int] = None,
    lastElectionEpoch: Option[Long] = None,
    lastHeartbeatEpoch: Option[Long] = None,
    currentLeader: Option[Int] = None,
    peers: List[LeaderConfig] = List.empty
)

object RaftElectionState {

  def apply(peers: List[LeaderConfig]): RaftElectionState = RaftElectionState().copy(peers = peers)

}

object RaftService {

  // TODO: election to peers
  // TODO: Mutex
  // TODO: dynamic list of peers
  def make[F[_]: Async: Clock: Logger](state: AtomicCell[F, RaftElectionState]): RaftService[F] = new RaftService[F] {
    given logger: Logger[F] = Slf4jLogger.getLogger[F]
    private val minTimeout  = 1500L.millis
    private val maxTimeout  = 3000L.millis

    override def runElectionTimer(): F[Unit] = for {
      _ <- Logger[F].debug("Starting raft election handler")
      _ <- bgThread() // runs recursively, no need for foreverM
    } yield ()

    def startElection: F[Unit] = {
      for {
        now <- Temporal[F].realTime
        _   <- Logger[F].info("Starting election")
//        _ <- state.update { s =>
//               val newTerm = s.term + 1
//               s.copy(
//                 ownState = Candidate,
//                 term = newTerm,
//                 votedFor = Some(selfId),
//                 lastElectionEpoch = Some(now.toMillis)
//               )
//             }
        // Here you could broadcast RequestVote RPCs to all peers in `state.get.peers`
      } yield ()
    }

    override def handleVote(req: RequestVote): F[RequestVoteResponse] = {
      for {
        _                 <- logger.debug(s"Received vote request from ${req.candidateId} for term ${req.term}")
        ourTerm           <- getTerm
        ts                <- Temporal[F].realTime
        votedForCandidate <- getVotedFor
        voteGranted        = req.term >= ourTerm && (votedForCandidate.isEmpty || votedForCandidate.get == req.candidateId)
        term <- if (voteGranted) {
                  setTerm(req.term)
                    >> setVotedFor(req.candidateId)
                    >> setLastElectionTimestamp(ts.toMillis)
                    >> setState(Follower)
                    >> Async[F].blocking(req.term)
                } else Async[F].blocking(ourTerm)
      } yield RequestVoteResponse(
        term = term,
        voteGranted = voteGranted
      )
    }

    override def handleHeartbeat(ts: Long): F[Unit] = {
      for {
        _        <- logger.debug(s"Trying to set heartbeat to $ts")
        ourState <- getState
        _ <- if (ourState != Leader) setLastHeartbeatTimestamp(ts) >> logger.debug(s"Setting heartbeat to $ts")
             else Async[F].unit
      } yield ()
    }

    private def bgThread(): F[Unit] = {
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
               logger.warn("No peers, skipping election timer")
             } else {
               Logger[F].debug(s"Election timer skipped: heartbeat or election recently occurred: timeSinceHeartbeat: $timeSinceHb, timeSinceElection: $timeSinceEl")
             }
        // Recursive
        _ <- bgThread()
      } yield ()
    }

    private def getState: F[RaftState]               = state.get.map(_.ownState)
    private def setState(st: RaftState): F[Unit]     = state.update(_.copy(ownState = st))
    private def getTerm: F[Int]                      = state.get.map(_.term)
    private def setTerm(term: Int): F[Unit]          = state.update(_.copy(term = term))
    private def getVotedFor: F[Option[Int]]          = state.get.map(_.votedFor)
    private def setVotedFor(candidate: Int): F[Unit] = state.update(_.copy(votedFor = Some(candidate)))
//    private def getLastElectionTimestamp(): F[Long]          = state.get.map(_.lastElectionEpoch)
    private def setLastElectionTimestamp(ts: Long): F[Unit] = state.update(_.copy(lastElectionEpoch = Some(ts)))
//    private def getLastHeartbeatTimestamp(): F[Option[Long]] = state.get.map(_.lastHeartbeatEpoch)
    private def setLastHeartbeatTimestamp(ts: Long): F[Unit] = state.update(_.copy(lastHeartbeatEpoch = Some(ts)))
  }

}
