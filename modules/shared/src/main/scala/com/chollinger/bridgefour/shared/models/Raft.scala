package com.chollinger.bridgefour.shared.models

import io.circe.Decoder
import io.circe.Encoder
import org.http4s.EntityDecoder
import org.latestbit.circe.adt.codec.JsonTaggedAdt

enum RaftState {

  case Leader
  case Follower
  case Candidate

}

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
