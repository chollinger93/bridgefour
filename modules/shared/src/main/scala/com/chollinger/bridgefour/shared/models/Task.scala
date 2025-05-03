package com.chollinger.bridgefour.shared.models

import com.chollinger.bridgefour.shared.models.IDs._
import com.chollinger.bridgefour.shared.models.Job._
import io.circe.{Decoder, Encoder}
import org.latestbit.circe.adt.codec._

object Task {

  sealed trait TaskConfig derives Encoder.AsObject, Decoder {

    def jobClass: JobClass

    def input: FilePath

    def output: DirPath

    def userSettings: Map[String, String]

  }

  // Instructs a worker to work on a task for a given job
  case class AssignedTaskConfig(
      taskId: TaskIdTuple,
      // SlotIds are owned by the worker, as workers return a list of slot IDs via WorkerState,
      // namely GET http://{{rockServer}}:{{rockPort}}/worker/state
      slotId: SlotIdTuple,
      input: FilePath,
      output: DirPath,
      jobClass: JobClass,
      userSettings: Map[String, String]
  ) extends TaskConfig derives Encoder.AsObject, Decoder {

    val outputFile: FilePath =
      s"$output/part-${taskId.id}-${taskId.jobId}.txt"

  }

  object AssignedTaskConfig {

    def apply(
        taskId: TaskIdTuple,
        slotId: SlotIdTuple,
        input: FilePath,
        job: SystemJobConfig,
        userSettings: Map[String, String]
    ): AssignedTaskConfig =
      AssignedTaskConfig(
        taskId = taskId, slotId = slotId, input = input, output = job.output, jobClass = job.jobClass,
        userSettings = userSettings
      )

  }

  case class UnassignedTaskConfig(
      input: FilePath,
      output: DirPath,
      jobClass: JobClass,
      userSettings: Map[String, String]
  ) extends TaskConfig derives Encoder.AsObject, Decoder

  object UnassignedTaskConfig {

    def apply(input: FilePath, job: JobConfig): UnassignedTaskConfig =
      UnassignedTaskConfig(input, job.output, job.jobClass, job.userSettings)

    def apply(assigned: AssignedTaskConfig): UnassignedTaskConfig =
      UnassignedTaskConfig(assigned.input, assigned.output, assigned.jobClass, assigned.userSettings)

  }

  case class TaskAssignmentTuple(
      assigned: List[AssignedTaskConfig],
      notAssigned: List[UnassignedTaskConfig]
  )

  enum AssignmentStatus derives JsonTaggedAdt.Codec {

    case FullyAssigned
    case PartiallyAssigned
    case NotAssigned

  }

  object AssignmentStatus {

    def needsAssignments(status: AssignmentStatus): Boolean =
      status == AssignmentStatus.NotAssigned || status == AssignmentStatus.PartiallyAssigned

  }

}
