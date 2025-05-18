package com.chollinger.bridgefour.shared.models

import cats.effect.kernel.Concurrent
import com.chollinger.bridgefour.shared.models.Config.LeaderConfig
import io.circe.Decoder
import io.circe.Encoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.EntityDecoder
import org.http4s.EntityEncoder

enum RaftState {

  case Leader
  case Follower
  case Candidate

}

case class RaftElectionState(
    ownId: Int,
    term: Int = 0,
    ownState: RaftState = RaftState.Follower,
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

final case class HeartbeatRequest(
    term: Int,
    currentLeader: Int,
    ts: Long
) derives Encoder.AsObject,
      Decoder

final case class RequestVote(
    term: Int,
    candidateId: Int
) derives Encoder.AsObject,
      Decoder

final case class RequestVoteResponse(
    term: Int,
    voteGranted: Boolean
) derives Encoder.AsObject,
      Decoder

trait RaftEncoders[F[_]: Concurrent] {

  given EntityEncoder[F, RequestVote] = jsonEncoderOf[F, RequestVote]
  given EntityDecoder[F, RequestVote] = accumulatingJsonOf[F, RequestVote]

  given EntityEncoder[F, HeartbeatRequest] = jsonEncoderOf[F, HeartbeatRequest]
  given EntityDecoder[F, HeartbeatRequest] = accumulatingJsonOf[F, HeartbeatRequest]

  given EntityEncoder[F, RequestVoteResponse] = jsonEncoderOf[F, RequestVoteResponse]

  given EntityDecoder[F, RequestVoteResponse] = accumulatingJsonOf[F, RequestVoteResponse]

  given EntityEncoder[F, RaftElectionState] = jsonEncoderOf[F, RaftElectionState]

}
