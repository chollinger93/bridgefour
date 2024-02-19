package com.chollinger.bridgefour.kaladin.programs

import scala.collection.immutable

import cats.*
import cats.effect.{Async, Concurrent}
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.kaladin.state.JobDetailsStateMachine
import com.chollinger.bridgefour.shared.extensions.*
import com.chollinger.bridgefour.shared.jobs.LeaderCreator
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.persistence.Persistence
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import io.circe.Json
import org.http4s.*
import org.http4s.circe.accumulatingJsonOf
import org.http4s.client.Client
import org.typelevel.log4cats.Logger

sealed trait JobController[F[_]] {

  @EventuallyConsistent
  def submitJob(cfg: UserJobConfig): F[JobDetails]

  @EventuallyConsistent
  def stopJob(jobId: JobId): F[ExecutionStatus]

  @StaleReads
  def listRunningJobs(): F[List[JobDetails]]

  @EventuallyConsistent
  def getJobResult(jobId: JobId): F[ExecutionStatus]

  @StaleReads
  def getJobDetails(jobId: JobId): F[Either[ExecutionStatus, JobDetails]]

  @StaleReads
  def calculateResults(jobId: JobId): F[Either[ExecutionStatus, Json]]

}

case class JobControllerService[F[_]: ThrowableMonadError: Concurrent: Async: Logger](
    client: Client[F],
    jobState: Persistence[F, JobId, JobDetails],
    stateMachine: JobDetailsStateMachine[F],
    leaderJob: LeaderCreator[F],
    cfg: ServiceConfig
) extends JobController[F] {

  val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]
  val sF: Async[F]                = implicitly[Async[F]]

  given EntityDecoder[F, Map[TaskId, ExecutionStatus]] = accumulatingJsonOf[F, Map[TaskId, ExecutionStatus]]
  given EntityDecoder[F, ExecutionStatus]              = accumulatingJsonOf[F, ExecutionStatus]

  // API
  override def submitJob(jCfg: UserJobConfig): F[JobDetails] = for {
    _ <- Logger[F].debug(s"Submitting job: $jCfg")
    // Build the initial state
    jd <- stateMachine.initialState(jCfg)
    _  <- Logger[F].debug(s"Initial state ${jd.jobId}: $jd")
    // Persist
    // TODO: signal
    _ <- jobState.put(jd.jobId, jd)
  } yield jd

  override def stopJob(jobId: JobId): F[ExecutionStatus] = ???

  override def listRunningJobs(): F[List[JobDetails]] =
    jobState.values().map(_.filter(s => !ExecutionStatus.available(s.executionStatus)))

  override def getJobResult(jobId: JobId): F[ExecutionStatus] = for {
    _  <- Logger[F].debug(s"Getting job result for $jobId")
    jd <- jobState.get(jobId)
    jdN <- jd match {
             case Some(j) => sF.blocking(j.executionStatus)
             case _       => sF.blocking(ExecutionStatus.Missing)
           }
    _ <- Logger[F].debug(s"Job result for $jdN")
  } yield jdN

  def getJobDetails(jobId: JobId): F[Either[ExecutionStatus, JobDetails]] = for {
    _  <- Logger[F].debug(s"Updating job status for $jobId")
    jd <- jobState.get(jobId)
    jdN <- jd match {
             case Some(j) => sF.blocking(Right(j))
             case _       => sF.blocking(Left(ExecutionStatus.Missing))
           }
  } yield jdN

  def calculateResults(jobId: JobId): F[Either[ExecutionStatus, Json]] = for {
    _  <- Logger[F].debug(s"Trying to get data for $jobId")
    jd <- jobState.get(jobId)
    jdN <- jd match {
             // TODO: worker queue
             case Some(j) if ExecutionStatus.finished(j.executionStatus) =>
               leaderJob.makeJob(j).collectResults().map(r => Right(r))
             case Some(j) => sF.blocking(Left(j.executionStatus))
             case _       => sF.blocking(Left(ExecutionStatus.Missing))
           }
  } yield jdN

}
