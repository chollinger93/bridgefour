package com.chollinger.bridgefour.kaladin.services

import cats.Monad
import cats.implicits.*
import com.chollinger.bridgefour.shared.exceptions.Exceptions.NoFilesAvailableException
import com.chollinger.bridgefour.shared.extensions.takeN
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Task.*
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
import org.typelevel.log4cats.Logger

trait JobSplitter[F[_]] {

  def splitJobIntoTasks(jd: JobDetails, workers: List[WorkerState], ids: IdMaker[F, Int]): F[TaskAssignmentTuple]

}

object JobSplitterService {

  def make[F[_]: ThrowableMonadError: Logger](): JobSplitter[F] = new JobSplitter[F] {

    val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]
    val mF: Monad[F]                = implicitly[Monad[F]]

    private def validateTaskSplit(workers: List[WorkerState], files: List[FilePath]): F[List[WorkerState]] = {
      val fWorkers = workers.filter(_.availableSlots.nonEmpty)
      if (fWorkers.isEmpty)
        Logger[F].warn(s"No workers available in pool! Failed to assign ${files.size} tasks.") >> mF.pure(List.empty)
      else if (files.isEmpty)
        err.raiseError[List[WorkerState]](new NoFilesAvailableException("No files available to process"))
      else mF.pure(fWorkers)
    }

    private def assignTasksToWorkers(
        jd: JobDetails,
        workers: List[WorkerState],
        files: List[FilePath],
        taskIds: List[Int],
        assignments: List[AssignedTaskConfig] = List.empty
    ): TaskAssignmentTuple = {
      if (workers.isEmpty)
        return TaskAssignmentTuple(
          assignments,
          files.map(f => UnassignedTaskConfig(f, jd.jobConfig))
        )
      val w       = workers.head
      val aT      = files.takeN(w.availableSlots.size)
      val iT      = taskIds.takeN(w.availableSlots.size)
      val open    = aT._2
      val openIds = iT._2
      val newAssignments = aT._1.zipWithIndex.map { case (f, i) =>
        AssignedTaskConfig(
          taskId = TaskIdTuple(iT._1(i), jd.jobId),
          slotId = SlotIdTuple(w.availableSlots(i), w.id),
          input = f,
          job = jd.jobConfig,
          userSettings = Map()
        )
      }
      assignTasksToWorkers(jd, workers.tail, open, openIds, assignments ++ newAssignments)
    }

    /** Splits workers into tasks.
      *
      * Each worker is greedy and tries to fill all available slot.
      *
      * TODO: assignment abstraction / algorithms
      *
      * If either workers or slots are unavailable, this will fail
      *
      * Workers own their slot IDs and present them w/in WorkerState
      *
      * The leader is responsible for generating Task and Job IDs
      *
      * @param JobDetails
      *   Full JobDetails. Does not change state!
      * @param workers
      *   List of all workers, usually from the config
      * @return
      *   A tuple of (assigned, unassigned) tasks
      */
    override def splitJobIntoTasks(
        jd: JobDetails,
        workers: List[WorkerState],
        ids: IdMaker[F, Int]
    ): F[TaskAssignmentTuple] = {
      val files = jd.openTasks.map(_.input)
      for {
        fWorkers <- validateTaskSplit(workers, files)
        taskIds  <- ids.makeIds(files.size)
        tasks     = assignTasksToWorkers(jd, fWorkers, files, taskIds)
        _ <-
          Logger[F].debug(
            s"Split ${jd.openTasks.size} open into ${tasks.assigned.size} assigned and ${tasks.notAssigned.size} unassigned tasks"
          )
      } yield tasks
    }

  }

}
