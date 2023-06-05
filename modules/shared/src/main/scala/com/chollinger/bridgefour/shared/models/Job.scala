package com.chollinger.bridgefour.shared.models

import cats.effect.kernel.Sync
import com.chollinger.bridgefour.shared.jobs.JobClass
import com.chollinger.bridgefour.shared.models.IDs.JobId
import com.chollinger.bridgefour.shared.models.IDs.SlotId
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskId
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Task.{AssignedTaskConfig, AssignmentStatus, UnassignedTaskConfig}
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import io.circe.Decoder
import io.circe.Encoder
import org.latestbit.circe.adt.codec.*

object Job {

  type DirPath = String

  type FilePath = String

  type JobName = String

  sealed trait JobConfig {

    def name: JobName

    def input: DirPath

    def output: DirPath

    def jobClass: JobClass

    def userSettings: Map[String, String]

  }
  // The input a user provides for a job
  case class UserJobConfig(
      name: JobName,
      jobClass: JobClass,
      input: DirPath,
      output: DirPath,
      userSettings: Map[String, String]
  ) extends JobConfig
      derives Encoder.AsObject,
        Decoder

  // The machine-generated JobConfig, with assigned tasks
  case class SystemJobConfig(
      id: JobId,
      name: JobName,
      jobClass: JobClass,
      input: DirPath,
      output: DirPath,
      userSettings: Map[String, String]
  ) // , tasks: List[TaskConfig]
      extends JobConfig

  object SystemJobConfig {

    def apply(id: JobId, cfg: UserJobConfig): SystemJobConfig =
      SystemJobConfig(id, cfg.name, cfg.jobClass, cfg.input, cfg.output, cfg.userSettings)

  }

  // Tracker for a (partially) assigned job
  case class JobDetails(
      jobId: JobId,
      jobConfig: SystemJobConfig,
      executionStatus: ExecutionStatus,
      assignmentStatus: AssignmentStatus,
      assignedTasks: List[AssignedTaskConfig],
      openTasks: List[UnassignedTaskConfig],
      completedTasks: List[AssignedTaskConfig]
  ) derives Encoder.AsObject,
        Decoder

  object JobDetails {

    def empty(
        jobId: JobId,
        jobConfig: SystemJobConfig,
        openTasks: List[UnassignedTaskConfig]
    ): JobDetails = JobDetails(
      jobId = jobId,
      jobConfig = jobConfig,
      executionStatus = ExecutionStatus.NotStarted,
      assignmentStatus = AssignmentStatus.NotAssigned,
      assignedTasks = List.empty,
      openTasks = openTasks,
      completedTasks = List.empty
    )

  }

  // A TaskState is the terminal state of a job, i.e. it should (generally) never transition to "InProgress" in practice
  case class TaskState(id: TaskIdTuple, status: ExecutionStatus) derives Encoder.AsObject, Decoder

}
