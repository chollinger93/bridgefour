package com.chollinger.bridgefour.kaladin.programs

import java.io.File

import scala.collection.immutable
import scala.collection.mutable.ArrayBuffer

import cats.*
import cats.effect.implicits.*
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.std.{Mutex, UUIDGen}
import cats.effect.{Async, Concurrent, Sync}
import cats.implicits.*
import cats.syntax.all.*
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.parallel.catsSyntaxParallelTraverse1
import cats.syntax.traverse.toTraverseOps
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.kaladin.services.{IdMaker, JobSplitter, WorkerOverseer}
import com.chollinger.bridgefour.kaladin.state.JobDetailsStateMachine
import com.chollinger.bridgefour.shared.exceptions.Exceptions.{InvalidWorkerConfigException, NoFilesAvailableException, OrphanTaskException}
import com.chollinger.bridgefour.shared.extensions.takeN
import com.chollinger.bridgefour.shared.jobs.LeaderCreator
import com.chollinger.bridgefour.shared.models.Config.{RockConfig, WorkerConfig}
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.{AssignedTaskConfig, AssignmentStatus}
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.models.{Status, WorkerStatus}
import com.chollinger.bridgefour.shared.persistence.{Counter, Persistence}
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import io.circe.Json
import io.circe.parser.decode
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed trait JobController[F[_]] {

  def startJob(cfg: UserJobConfig): F[JobDetails]

  def stopJob(jobId: JobId): F[ExecutionStatus]

  def getJobResult(jobId: JobId): F[ExecutionStatus]

  def checkAndUpdateJobProgress(jobId: JobId): F[ExecutionStatus]

  def getJobDetails(jobId: JobId): F[Either[ExecutionStatus, JobDetails]]

  def calculateResults(jobId: JobId): F[Either[ExecutionStatus, Json]]

}

case class JobControllerService[F[_]: ThrowableMonadError: Concurrent: Async: Logger](
    client: Client[F],
    ids: IdMaker[F, Int],
    workerOverseerService: WorkerOverseer[F],
    splitter: JobSplitter[F],
    state: Persistence[F, JobId, JobDetails],
    stateMachine: JobDetailsStateMachine[F],
    leaderJob: LeaderCreator[F],
    lock: Mutex[F],
    cfg: ServiceConfig
) extends JobController[F] {

  val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]
  val sF: Async[F]                = implicitly[Async[F]]

  given EntityDecoder[F, Map[TaskId, ExecutionStatus]] = accumulatingJsonOf[F, Map[TaskId, ExecutionStatus]]
  given EntityDecoder[F, ExecutionStatus]              = accumulatingJsonOf[F, ExecutionStatus]

  // IO
  private def startTasksOnWorkers(
      tasks: Map[WorkerId, List[AssignedTaskConfig]]
  ): F[Map[TaskId, ExecutionStatus]] = {
    val reqs = tasks.map { case (wId, tasks) =>
      val wCfg = cfg.workers.find(_.id == wId)
      if (wCfg.isEmpty) err.raiseError(new InvalidWorkerConfigException(s"Invalid worker config for id $wId"))
      // TODO: unsafeFromString
      (
        wId,
        Request[F](method = Method.POST, uri = Uri.unsafeFromString(s"${wCfg.get.uri()}/task/start")).withEntity(tasks)
      )
    }
    reqs.map { case (wId, r) =>
      err
        .handleErrorWith(client.expect[Map[TaskId, ExecutionStatus]](r))(e =>
          Logger[F].error(e)(s"Starting task on worker $wId failed") >>
            sF.blocking(tasks(wId).map(t => (t.taskId.id, ExecutionStatus.Error)).toMap)
        )
    }.toList.sequence.map(_.foldLeft(Map[TaskId, ExecutionStatus]()) { case (m1, m2) => m1 ++ m2 })
  }

  private def getTaskStatus(tc: AssignedTaskConfig): F[(TaskId, ExecutionStatus)] = {
    val wId  = tc.slotId.workerId
    val wCfg = cfg.workers.find(_.id == wId)
    if (wCfg.isEmpty) err.raiseError(new InvalidWorkerConfigException(s"Invalid worker config for id $wId"))
    val r =
      Request[F](method = Method.GET, uri = Uri.unsafeFromString(s"${wCfg.get.uri()}/task/status/${tc.slotId.id}"))
    err
      .handleErrorWith(client.expect[ExecutionStatus](r))(e =>
        Logger[F].error(e)(s"Getting task status for task ${tc.taskId} on worker $wId failed") >>
          sF.blocking(ExecutionStatus.Error)
      )
      .map(s => (tc.taskId.id, s))
  }

  /** Maps submitted execution status from the worker API back to its AssignedTaskConfig
    *
    * Will raise an error if tasks cannot be found. This can only happen if the state messes up somehow, at which point
    * the job has essentially failed
    *
    * @param assignedTasks
    *   Tasks assigned during split
    * @param submittedTasks
    *   Remote execution
    * @return
    *   Config map
    * @throws OrphanTaskException
    *   If configs are missing
    */
  private def mapTaskStatusToConfig(
      assignedTasks: List[AssignedTaskConfig],
      submittedTasks: Map[TaskId, ExecutionStatus]
  ): F[Map[AssignedTaskConfig, ExecutionStatus]] = {
    val submittedTaskConfig = submittedTasks.map { case (id, s) => (assignedTasks.find(_.taskId.id == id), s) }
      .filter(_._1.isDefined)
      .map { case (k, v) => (k.get, v) }
    if (submittedTasks.size != submittedTaskConfig.size) {
      err.raiseError(
        OrphanTaskException(
          s"Submitted ${submittedTasks.size} tasks but only found ${submittedTaskConfig.size} task configurations out of ${assignedTasks.size}!"
        )
      )
    } else sF.pure(submittedTaskConfig)
  }

  private def logTaskChange(oldJd: JobDetails, newJd: JobDetails): F[Unit] = for {
    _ <- Logger[F].debug(
           s"JobID ${newJd.jobId} State Change: ExecutionStatus: ${oldJd.executionStatus} => ${newJd.executionStatus}"
         )
    _ <-
      Logger[F].debug(
        s"JobID ${newJd.jobId} State Change: AssignmentStatus: ${oldJd.assignmentStatus} => ${newJd.assignmentStatus}"
      )
    _ <-
      Logger[F].debug(
        s"JobID ${newJd.jobId} State Change: " +
          s"Completed: ${oldJd.completedTasks.size}->${newJd.completedTasks.size}, " +
          s"Open: ${oldJd.openTasks.size}->${newJd.openTasks.size}, " +
          s"Assigned: ${oldJd.assignedTasks.size}->${newJd.assignedTasks.size}"
      )
    _ <-
      Logger[F].debug(
        s"JobID ${newJd.jobId} State Change: " +
          s"Completed: ${newJd.completedTasks.map(_.input)}, " +
          s"Open: ${newJd.openTasks.map(_.input)}, " +
          s"Assigned: ${newJd.assignedTasks.map(_.input)}"
      )

  } yield ()

  // Assigns new tasks, starts them, and changes JD state. Does not store state. This will fail if there are no tasks to assign
  private def assignTasksAndStartAllWithStateChange(jd: JobDetails): F[JobDetails] = for {
    // Update Cluster State
    workers <- workerOverseerService.listAvailableWorkers()
    // Assign new tasks
    tasks <- splitter.splitJobIntoTasks(jd, workers, ids)
    _     <- Logger[F].debug(s"Starting tasks: ${tasks.assigned.map(t => (t.taskId, t.slotId))})}")
    // Talk to workers
    submittedTasks      <- startTasksOnWorkers(tasks.assigned.groupBy(_.slotId.workerId))
    _                   <- Logger[F].debug(s"Submitted task requests: $submittedTasks")
    submittedTaskConfig <- mapTaskStatusToConfig(tasks.assigned, submittedTasks)
    _                   <- Logger[F].debug(s"Submitted task state for ${jd.jobId}: $submittedTaskConfig")
    nJd                  = stateMachine.transition(jd, submittedTaskConfig)
    _                   <- logTaskChange(jd, nJd)
  } yield nJd

  // Get each worker's task status for this job, update all states, store, and return it
  private def updateJobDetailsFromWorkersAndAssign(jd: JobDetails): F[JobDetails] = for {
    // Get each worker's status
    _                   <- Logger[F].debug(s"Job ${jd.jobId} execution status: ${jd.executionStatus}")
    currentTaskStatus   <- jd.assignedTasks.parTraverse(tc => getTaskStatus(tc))
    _                   <- Logger[F].debug(s"Raw task state for ${jd.jobId}: $currentTaskStatus")
    assignedTaskStatus   = currentTaskStatus.toMap
    submittedTaskConfig <- mapTaskStatusToConfig(jd.assignedTasks, assignedTaskStatus)
    _                   <- Logger[F].debug(s"Current task state for ${jd.jobId}: $submittedTaskConfig")
    // Reflect the current change in worker task status in the JD
    jd1 = stateMachine.transition(jd, submittedTaskConfig)
    _  <- logTaskChange(jd, jd1)
    // Assign & start new tasks
    jd2 <-
      if (AssignmentStatus.needsAssignments(jd1.assignmentStatus) && !ExecutionStatus.finished(jd1.executionStatus))
        assignTasksAndStartAllWithStateChange(jd1)
      else sF.blocking(jd1)
    // Persist
    _ <- state.put(jd2.jobId, jd2)
  } yield jd2

  // API
  override def startJob(jCfg: UserJobConfig): F[JobDetails] =
    lock.lock.surround {
      for {
        _ <- Logger[F].debug(s"Starting job: $jCfg")
        // Build the initial state
        jd <- stateMachine.initialState(jCfg)
        // Assign & start new tasks
        jd2 <- assignTasksAndStartAllWithStateChange(jd)
        _   <- state.put(jd2.jobId, jd2)
      } yield jd2
    }

  override def stopJob(jobId: JobId): F[ExecutionStatus] = ???

  override def getJobResult(jobId: JobId): F[ExecutionStatus] = for {
    _  <- Logger[F].debug(s"Getting job result for $jobId")
    jd <- state.get(jobId)
    jdN <- sF.blocking(jd match {
             case Some(j) => j.executionStatus
             case _       => ExecutionStatus.Missing
           })
  } yield jdN

  def checkAndUpdateJobProgress(jobId: JobId): F[ExecutionStatus] =
    lock.lock.surround {
      for {
        _  <- Logger[F].debug(s"Updating job status for $jobId")
        jd <- state.get(jobId)
        jdN <- jd match {
                 case Some(j) if !ExecutionStatus.finished(j.executionStatus) =>
                   updateJobDetailsFromWorkersAndAssign(j).map(_.executionStatus)
                 case Some(j) => sF.blocking(j.executionStatus)
                 case _       => sF.blocking(ExecutionStatus.Missing)
               }
      } yield jdN
    }

  def getJobDetails(jobId: JobId): F[Either[ExecutionStatus, JobDetails]] = for {
    _  <- Logger[F].debug(s"Updating job status for $jobId")
    jd <- state.get(jobId)
    jdN <- jd match {
             case Some(j) => sF.blocking(Right(j))
             case _       => sF.blocking(Left(ExecutionStatus.Missing))
           }
  } yield jdN

  def calculateResults(jobId: JobId): F[Either[ExecutionStatus, Json]] = for {
    _  <- Logger[F].debug(s"Trying to get data for $jobId")
    jd <- state.get(jobId)
    jdN <- jd match {
             // TODO: worker queue
             case Some(j) if ExecutionStatus.finished(j.executionStatus) =>
               leaderJob.makeJob(j).collectResults().map(r => Right(r))
             case Some(j) => sF.blocking(Left(j.executionStatus))
             case _       => sF.blocking(Left(ExecutionStatus.Missing))
           }
  } yield jdN

}
