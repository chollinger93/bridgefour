package com.chollinger.bridgefour.kaladin.programs

import scala.concurrent.duration.DurationDouble
import scala.language.postfixOps
import scala.util.boundary

import cats.*
import cats.effect.implicits.*
import cats.effect.std.Mutex
import cats.effect.{Async, Concurrent, Sync}
import cats.implicits.*
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.parallel.catsSyntaxParallelTraverse1
import cats.syntax.traverse.toTraverseOps
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.kaladin.services.{ClusterOverseer, IdMaker, JobSplitter}
import com.chollinger.bridgefour.kaladin.state.JobDetailsStateMachine
import com.chollinger.bridgefour.shared.exceptions.Exceptions.{InvalidWorkerConfigException, OrphanTaskException}
import com.chollinger.bridgefour.shared.extensions.StronglyConsistent
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.JobDetails
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.{AssignedTaskConfig, AssignmentStatus}
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.Persistence
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.client.Client
import org.typelevel.log4cats.Logger

import boundary.break

sealed trait ClusterController[F[_]] {

  @StronglyConsistent
  def updateClusterStatus(): F[ClusterState]

  @StronglyConsistent
  def updateAllJobStates(): F[List[JobDetails]]

  @StronglyConsistent
  def rebalanceUnassignedTasks(): F[List[JobDetails]]

  def startFibers(): F[Unit]

}

case class ClusterControllerImpl[F[_]: ThrowableMonadError: Concurrent: Async: Logger](
    client: Client[F],
    clusterOverseer: ClusterOverseer[F],
    splitter: JobSplitter[F],
    jobState: Persistence[F, JobId, JobDetails],
    clusterState: Persistence[F, ClusterId, ClusterState],
    ids: IdMaker[F, Int],
    stateMachine: JobDetailsStateMachine[F],
    lck: Mutex[F],
    cfg: ServiceConfig
) extends ClusterController[F] {

  private val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]
  private val sF: Async[F]                = implicitly[Async[F]]

  given EntityDecoder[F, Map[TaskId, ExecutionStatus]] = accumulatingJsonOf[F, Map[TaskId, ExecutionStatus]]

  given EntityDecoder[F, ExecutionStatus] = accumulatingJsonOf[F, ExecutionStatus]

  // ############################
  // Cluster health
  // ############################
  private def getAndUpdateClusterStatus(): F[ClusterState] = for {
    _  <- Logger[F].debug("Updating cluster status")
    cs <- clusterOverseer.getClusterState()
    _  <- clusterState.put(cfg.clusterId, cs)
    _  <- Logger[F].debug(s"Cluster state for ${cfg.clusterId}: $cs")
  } yield cs

  // ############################
  // Assignment
  // ############################
  private def startTasksOnWorkers(
      tasks: Map[WorkerId, List[AssignedTaskConfig]]
  ): F[Map[TaskId, ExecutionStatus]] = {
    val reqs = tasks.map { case (wId, tasks) =>
      val wCfg = cfg.workers.find(_.id == wId)
      if (wCfg.isEmpty) err.raiseError(InvalidWorkerConfigException(s"Invalid worker config for id $wId"))
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

  /** Assigns new tasks, starts them, and changes JD state.
    *
    * Does not store state. This will fail if there are no tasks to assign
    * @param jd
    * @return
    *   Updated JD
    */
  private def splitAndAssignTasks(jd: JobDetails, workers: List[WorkerState]): F[JobDetails] = for {
    _     <- Logger[F].debug(s"Job ${jd.jobId} does need assignments")
    tasks <- splitter.splitJobIntoTasks(jd, workers, ids)
    _     <- Logger[F].debug(s"Starting tasks: ${tasks.assigned.map(t => (t.taskId, t.slotId))})}")
    // Talk to workers
    submittedTasks      <- startTasksOnWorkers(tasks.assigned.groupBy(_.slotId.workerId))
    _                   <- Logger[F].debug(s"Submitted task requests: $submittedTasks")
    submittedTaskConfig <- mapTaskStatusToConfig(tasks.assigned, submittedTasks)
    _                   <- Logger[F].debug(s"Submitted task state for ${jd.jobId}: $submittedTaskConfig")
    nJd                  = stateMachine.transition(jd, submittedTaskConfig)
    _                   <- stateMachine.log("assign")(jd, nJd)
  } yield nJd

  private def assignTasksAndStartAllWithStateChange(jd: JobDetails): F[JobDetails] = for {
    // Get Cluster State
    // TODO: read from cache and seed initially
    clusterState <- getAndUpdateClusterStatus()
    workers       = clusterState.workers.values.toList
    // Assign new tasks
    nJd <- jd.assignmentStatus match {
             case s if AssignmentStatus.needsAssignments(s) => splitAndAssignTasks(jd, workers)
             case _                                         => sF.pure(jd)
           }
  } yield nJd

  // ############################
  // Status
  // ############################
  private def getTaskStatus(tc: AssignedTaskConfig): F[(TaskId, ExecutionStatus)] = {
    val wId  = tc.slotId.workerId
    val wCfg = cfg.workers.find(_.id == wId)
    if (wCfg.isEmpty) err.raiseError(InvalidWorkerConfigException(s"Invalid worker config for id $wId"))
    val r =
      Request[F](method = Method.GET, uri = Uri.unsafeFromString(s"${wCfg.get.uri()}/task/status/${tc.slotId.id}"))
    err
      .handleErrorWith(client.expect[ExecutionStatus](r))(e =>
        Logger[F].error(e)(s"Getting task status for task ${tc.taskId} on worker $wId failed") >>
          sF.blocking(ExecutionStatus.Error)
      )
      .map(s => (tc.taskId.id, s))
  }

  // Maps submitted execution status from the worker API back to its AssignedTaskConfig
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

  // Get each worker's task status for this job, update all states, and return it
  private def getJobDetailsFromWorkers(jd: JobDetails): F[JobDetails] = for {
    // Get each worker's status
    _                   <- Logger[F].debug(s"[Update] Job ${jd.jobId} initial execution status: ${jd.executionStatus}")
    currentTaskStatus   <- jd.assignedTasks.parTraverse(tc => getTaskStatus(tc))
    _                   <- Logger[F].debug(s"[Update] Raw task state for ${jd.jobId}: $currentTaskStatus")
    assignedTaskStatus   = currentTaskStatus.toMap
    submittedTaskConfig <- mapTaskStatusToConfig(jd.assignedTasks, assignedTaskStatus)
    _                   <- Logger[F].debug(s"[Update] Current task state for ${jd.jobId}: $submittedTaskConfig")
    // Reflect the current change in worker task status in the JD
    jd1 = stateMachine.transition(jd, submittedTaskConfig)
    _  <- stateMachine.log("update")(jd, jd1)
  } yield jd1

  private def updateInProgressJobStatus(jd: JobDetails): F[JobDetails] = {
    if (!ExecutionStatus.finished(jd.executionStatus)) {
      getJobDetailsFromWorkers(jd)
    } else {
      sF.blocking(jd)
    }
  }

  /** Returns a job's status by polling its workers, if it exists.
    *
    * @param jobId
    *   JobId
    * @return
    *   Status if the job exists
    */
  private def getJobStatus(jobId: JobId): F[Option[JobDetails]] = for {
    _  <- Logger[F].debug(s"Updating job status for $jobId")
    jd <- jobState.get(jobId)
    jdN <- jd match {
             case Some(j) => updateInProgressJobStatus(j).map(j => Some(j))
             case _       => sF.blocking(None)
           }
    _ <- Logger[F].debug(s"Job status for $jobId => $jdN")
  } yield jdN

  // ############################
  // API
  // ############################
  override def updateClusterStatus(): F[ClusterState] = lck.lock.surround(getAndUpdateClusterStatus())

  override def updateAllJobStates(): F[List[JobDetails]] =
    lck.lock.surround {
      for {
        _         <- Logger[F].debug("Updating all job states")
        allJobs   <- jobState.values()
        openJobs   = allJobs.filter(j => !ExecutionStatus.finished(j.executionStatus)).map(_.jobId)
        newStates <- openJobs.parTraverse(getJobStatus)
        _         <- Logger[F].debug(s"Got ${newStates.flatten.size} new states")
        _ <- newStates.parTraverse {
               case Some(jd) => jobState.put(jd.jobId, jd)
               case _        => sF.unit
             }
      } yield newStates.flatten
    }

  override def rebalanceUnassignedTasks(): F[List[JobDetails]] = lck.lock.surround {
    for {
      _         <- Logger[F].debug("Rebalancing all jobs")
      allJobs   <- jobState.values()
      openJobs   = allJobs.filter(j => !ExecutionStatus.finished(j.executionStatus))
      _         <- Logger[F].debug(s"${openJobs.size} jobs need rebalancing")
      newStates <- openJobs.parTraverse(assignTasksAndStartAllWithStateChange)
      _         <- newStates.parTraverse(jd => jobState.put(jd.jobId, jd))
    } yield newStates
  }

  private def bgThread(): F[Unit] = for {
    _ <- err.handleErrorWith(updateClusterStatus().void)(t => Logger[F].error(s"Failed to update cluster status: $t"))
    _ <- err.handleErrorWith(rebalanceUnassignedTasks().void)(t => Logger[F].error(s"Failed to balance tasks: $t"))
    _ <- err.handleErrorWith(updateAllJobStates().void)(t => Logger[F].error(s"Failed to update job states: $t"))
    _ <- Logger[F].debug("Kaladin Background threads finished")
    _ <- Sync[F].sleep(5 seconds) // TODO: cfg
  } yield ()

  override def startFibers(): F[Unit] = for {
    _ <- Logger[F].debug("Starting Kaladin background threads")
    _ <- bgThread().foreverM
  } yield ()

}
