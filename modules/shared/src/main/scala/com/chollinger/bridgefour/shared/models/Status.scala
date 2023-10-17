package com.chollinger.bridgefour.shared.models

import org.latestbit.circe.adt.codec.JsonTaggedAdt

object Status {

  enum ExecutionStatus derives JsonTaggedAdt.Codec {

    case NotStarted

    case InProgress

    case Halted

    case Done

    case Error

    case Missing

  }

  object ExecutionStatus {

    def finished(s: ExecutionStatus): Boolean = s == ExecutionStatus.Done

    def completed(s: ExecutionStatus): Boolean = s == ExecutionStatus.Done || s == ExecutionStatus.Error

    def mapAvailable(s: ExecutionStatus): Boolean = {
      s match
        case ExecutionStatus.NotStarted | ExecutionStatus.InProgress | ExecutionStatus.Halted =>
          false
        case _ => true
    }

  }

}
