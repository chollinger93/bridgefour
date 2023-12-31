package com.chollinger.bridgefour.kaladin

import java.io.File
import java.nio.file.Files

import scala.collection.immutable.List
import scala.concurrent.duration.DurationDouble
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

import cats.data.Kleisli
import cats.effect.*
import cats.effect.kernel.Fiber
import cats.effect.std.UUIDGen
import cats.implicits.*
import cats.syntax.all.*
import cats.syntax.traverse.toTraverseOps
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.kaladin.TestUtils.MockIDMaker
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpDir
import com.chollinger.bridgefour.kaladin.TestUtils.createTmpFile
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.kaladin.services.IdMaker
import com.chollinger.bridgefour.kaladin.services.JobConfigParser
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task._
import com.chollinger.bridgefour.shared.models.Worker.SlotState
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.comcast.ip4s.*
import fs2.io.net.Network
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import concurrent.duration.DurationDouble
object TestUtils {

  def createTmpFile(dir: File, prefix: String = "test-", suffix: String = ".csv"): IO[File] = IO(
    File.createTempFile(prefix, suffix, dir)
  )

  def createTmpDir(name: String): IO[File] = IO(Files.createTempDirectory(name).toFile)

  object MockIDMaker {

    def make(baseId: Int = 100): IdMaker[IO, Int] = new IdMaker[IO, Int] {

      override def makeId(): IO[Int] = IO.pure(baseId)

      override def makeIds(n: Int): IO[List[Int]] = Range.inclusive(0, n).toList.traverse(i => IO.pure(i + baseId))

    }

  }

  object FileEndingSortedJobConfigParserService {

    // This is almost the regular implementation, but it sorts by file ending, making sure tests are deterministic
    def make(): JobConfigParser[IO] = (cfg: JobConfig) =>
      IO {
        val dir = new File(cfg.input)
        if (dir.exists && dir.isDirectory) {
          dir.listFiles.toList.sortBy(_.getName.split("\\.").last)
        } else {
          List.empty
        }
      }

  }

  object Http {

    given EntityDecoder[IO, AssignedTaskConfig]           = accumulatingJsonOf[IO, AssignedTaskConfig]
    given EntityEncoder[IO, Map[TaskId, ExecutionStatus]] = jsonEncoderOf[IO, Map[TaskId, ExecutionStatus]]

    val jobId                     = 100
    val workerId                  = 0
    val taskIdTuple               = TaskIdTuple(id = 10, jobId = 0)
    val inProgressTask: TaskState = TaskState(taskIdTuple, status = ExecutionStatus.InProgress)
    val usedSlot: SlotState =
      SlotState(
        SlotIdTuple(id = 0, workerId = 0),
        available = false,
        status = ExecutionStatus.Done,
        taskId = Some(taskIdTuple)
      )
    val openSlot: SlotState =
      SlotState(SlotIdTuple(id = 1, workerId = 0), available = true, status = ExecutionStatus.Missing, taskId = None)
    val halfUsedWorkerState: WorkerState = WorkerState(
      id = 0,
      slots = List(usedSlot, openSlot),
      allSlots = List(usedSlot.id.id, openSlot.id.id),
      availableSlots = List(openSlot.id.id),
      runningTasks = List(taskIdTuple)
    )

    private def httpRoutes(
        workerState: WorkerState,
        usedSlotIds: List[Int] = List(0),
        doneSlotIds: List[Int] = List.empty
    ): HttpRoutes[IO] =
      HttpRoutes.of[IO] {
        // The workers report 50% usage, with one task in progress
        case GET -> Root / "worker" / "state" =>
          Ok(
            workerState
          )
        // Within those tasks, only files ending with "f1" are reported as not done yet
        case req @ POST -> Root / "task" / "start" =>
          Ok(for {
            tasks <- req.as[List[AssignedTaskConfig]]
            _     <- IO.println(s"/task/start: ${tasks.map(_.input)}")
            res = tasks.map { t =>
                    if (t.input.endsWith("f1")) (t.taskId.id, ExecutionStatus.InProgress)
                    else if (t.input.endsWith("f3")) (t.taskId.id, ExecutionStatus.Done)
                    else (t.taskId.id, ExecutionStatus.Error)
                  }.toMap
          } yield res)
        case GET -> Root / "task" / "status" / IntVar(slotId) =>
          IO.println(s"Status: slot: $slotId => Used: $usedSlotIds, done: $doneSlotIds") >>
            Ok(
              if (usedSlotIds.contains(slotId)) ExecutionStatus.InProgress
              else if (doneSlotIds.contains(slotId)) ExecutionStatus.Done
              else ExecutionStatus.Missing
            )
        case GET -> Root / "worker" / "status" => Ok()
      }

    private def httpApp(
        workerState: WorkerState = halfUsedWorkerState,
        usedSlotIds: List[Int] = List(0),
        doneSlotIds: List[Int] = List.empty
    ): HttpApp[IO] = httpRoutes(workerState, usedSlotIds, doneSlotIds).orNotFound
    def mockClient(
        workerState: WorkerState = halfUsedWorkerState,
        usedSlotIds: List[Int] = List(0),
        doneSlotIds: List[Int] = List.empty
    ): Client[IO] = Client.fromHttpApp(httpApp(workerState, usedSlotIds, doneSlotIds))

  }

}
