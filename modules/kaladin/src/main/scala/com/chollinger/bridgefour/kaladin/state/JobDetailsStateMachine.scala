package com.chollinger.bridgefour.kaladin.state

import scala.collection.mutable.ArrayBuffer

import cats.Monad
import cats.implicits.*
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import com.chollinger.bridgefour.kaladin.services.{IdMaker, JobConfigParser, JobSplitter}
import com.chollinger.bridgefour.kaladin.state._
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus.finished
import com.chollinger.bridgefour.shared.models.Status.{ExecutionStatus, WorkerTaskStatus}
import com.chollinger.bridgefour.shared.models.Task.*
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.state.{StateMachine, StateMachineWithAction}
import org.typelevel.log4cats.Logger

// TODO: The state machines are simple FSMs, but do not model transitions and actions well and rely to heavily on the `JobDetails` model

trait JobDetailsStateMachine[F[_]] extends StateMachine[JobDetails, Map[AssignedTaskConfig, ExecutionStatus]] {

  def initialState(jCfg: UserJobConfig): F[JobDetails]

  // Every time new tasks are started
  def transition(
      jd: JobDetails,
      remoteTaskState: Map[AssignedTaskConfig, ExecutionStatus]
  ): JobDetails

}

class JobExecutionStatusStateMachine extends StateMachine[ExecutionStatus, JobDetails] {

  override def transition(initState: ExecutionStatus, jd: JobDetails): ExecutionStatus = initState match
    case _ if jd.assignedTasks.isEmpty && jd.openTasks.isEmpty && jd.completedTasks.isEmpty  => ExecutionStatus.Missing
    case _ if jd.assignedTasks.isEmpty && jd.openTasks.isEmpty && jd.completedTasks.nonEmpty => ExecutionStatus.Done
    case _ if jd.assignedTasks.isEmpty && jd.openTasks.nonEmpty                              => ExecutionStatus.Halted
    case _                                                                                   => ExecutionStatus.InProgress

}

class JobAssignmentStatusStateMachine extends StateMachine[AssignmentStatus, JobDetails] {

  override def transition(initState: AssignmentStatus, jd: JobDetails): AssignmentStatus = initState match
    case _ if jd.openTasks.isEmpty && jd.assignedTasks.nonEmpty => AssignmentStatus.FullyAssigned
    case _ if jd.openTasks.isEmpty && jd.assignedTasks.isEmpty  => AssignmentStatus.NotAssigned
    case _ if jd.openTasks.nonEmpty && jd.assignedTasks.isEmpty => AssignmentStatus.NotAssigned
    case _                                                      => AssignmentStatus.PartiallyAssigned

}

class JobListsChangeStateMachine extends StateMachine[JobDetails, Map[AssignedTaskConfig, ExecutionStatus]] {

  // Move open/progress/done tasks between states, based on recently submitted jobs
  override def transition(jd: JobDetails, event: Map[AssignedTaskConfig, ExecutionStatus]): JobDetails = {
    var done     = ArrayBuffer[AssignedTaskConfig](jd.completedTasks: _*)
    var open     = ArrayBuffer[UnassignedTaskConfig](jd.openTasks: _*)
    var progress = ArrayBuffer[AssignedTaskConfig](jd.assignedTasks: _*)
    event.foreach { case (cfg, s) =>
      val uCfg = UnassignedTaskConfig(cfg)
      // TODO: need to rethink the whole ID system. comparing by input file isn't good
      s match
        // Done tasks are stored as such and removed from in-progress
        case ExecutionStatus.Done =>
          open = open.filter(p => p.input != uCfg.input)
          progress = progress.filter(p => p.input != cfg.input)
          if (!done.map(_.input).contains(cfg.input)) done += cfg
        // Failed or somehow missed tasks get re-opened and assigned on the next iteration
        case ExecutionStatus.Error | ExecutionStatus.Missing | ExecutionStatus.Halted | ExecutionStatus.NotStarted =>
          progress = progress.filter(p => p.input != cfg.input)
          done = done.filter(p => p.input != cfg.input)
          if (!open.map(_.input).contains(uCfg.input)) open += uCfg
        // In progress tasks generally do not change state
        case ExecutionStatus.InProgress =>
          open = open.filter(p => p.input != uCfg.input)
          done = done.filter(p => p.input != cfg.input)
          if (!progress.map(_.input).contains(cfg.input)) progress += cfg
    }
    jd.copy(
      assignedTasks = progress.toList.distinct,
      openTasks = open.toList.distinct,
      completedTasks = done.toList.distinct
    )
  }

}

object JobDetailsStateMachine {

  def make[F[_]: Monad: Logger](
      ids: IdMaker[F, Int],
      jobConfigParser: JobConfigParser[F],
      splitter: JobSplitter[F]
  ): JobDetailsStateMachine[F] = new JobDetailsStateMachine[F] {

    val executionStatusStateMachine: JobExecutionStatusStateMachine   = JobExecutionStatusStateMachine()
    val assignmentStatusStateMachine: JobAssignmentStatusStateMachine = JobAssignmentStatusStateMachine()
    val jobListStateMachine: JobListsChangeStateMachine               = JobListsChangeStateMachine()

    override def initialState(jCfg: UserJobConfig): F[JobDetails] = {
      for {
        jobId     <- ids.makeId()
        _         <- Logger[F].debug(s"Chose jobId $jobId for initial job state from: $jCfg")
        sCfg       = SystemJobConfig.apply(jobId, jCfg)
        files     <- jobConfigParser.splitJobIntoFiles(jCfg)
        unassigned = files.map(f => UnassignedTaskConfig(f.getAbsolutePath, jCfg))
        tmpJd      = JobDetails.empty(jobId, sCfg, unassigned)
        tasks     <- splitter.splitJobIntoTasks(tmpJd, List.empty, ids)
      } yield JobDetails(
        jobId = jobId,
        jobConfig = sCfg,
        executionStatus = ExecutionStatus.NotStarted,
        assignmentStatus = AssignmentStatus.NotAssigned,
        assignedTasks = tasks.assigned,
        openTasks = tasks.notAssigned,
        completedTasks = List.empty
      )
    }

    override def transition(
        jd: JobDetails,
        remoteTaskState: Map[AssignedTaskConfig, ExecutionStatus]
    ): JobDetails = {
      val nJd = jobListStateMachine.transition(jd, remoteTaskState)

      nJd.copy(
        executionStatus = executionStatusStateMachine.transition(jd.executionStatus, nJd),
        assignmentStatus = assignmentStatusStateMachine.transition(jd.assignmentStatus, nJd)
      )
    }

  }

}
