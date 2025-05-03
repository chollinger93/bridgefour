package com.chollinger.bridgefour.shared.models

import io.circe.{Decoder, Encoder}

object IDs {

  // TODO: types
  type JobId     = Int
  type TaskId    = Int
  type ClusterId = Int
  type WorkerId  = Int
  type SlotId    = Int
  case class TaskIdTuple(id: TaskId, jobId: JobId) derives Encoder.AsObject, Decoder
  case class SlotIdTuple(id: SlotId, workerId: WorkerId) derives Encoder.AsObject, Decoder

}
