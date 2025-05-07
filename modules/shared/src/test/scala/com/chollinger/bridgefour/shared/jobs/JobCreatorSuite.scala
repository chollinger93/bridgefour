package com.chollinger.bridgefour.shared.jobs

import cats.effect.Async
import cats.effect.IO
import cats.effect.Sync
import com.chollinger.bridgefour.shared.TestJobs
import com.chollinger.bridgefour.shared.models.IDs.SlotIdTuple
import com.chollinger.bridgefour.shared.models.IDs.TaskIdTuple
import com.chollinger.bridgefour.shared.models.Job
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class NotAJob[F[_]]() {}

case class IOBridgeFourJob(cfg: AssignedTaskConfig) extends BridgeFourJob[IO] {

  def run(): IO[BackgroundTaskState] =
    IO.pure(
      BackgroundTaskState(id = cfg.userSettings("taskId").toInt, status = ExecutionStatus.Done)
    )

}

class JobCreatorSuite extends CatsEffectSuite {

  given unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  private lazy val creator = BridgeFourJobCreatorService.make[IO]()

  test("BridgeFourJobCreator creates a valid job") {
    // If it doesn't panic, it's fine
    val job = creator.makeJob(TestJobs.sampleJobClass, null)
    assert(job != null)
  }

  test("BridgeFourJobCreator creates a valid job with a concrete effect") {
    // If it doesn't panic, it's fine
    val job = creator.makeJob("com.chollinger.bridgefour.shared.jobs.IOBridgeFourJob", null)
    assert(job != null)
  }

  test("BridgeFourJobCreator doesn't create an missing job") {
    val ex = intercept[java.lang.ClassNotFoundException] {
      creator.makeJob("fake.class", null)
    }
    assert(clue(ex.getMessage).contains("fake.class"))
  }

  test("BridgeFourJobCreator doesn't create an invalid job") {
    val ex = intercept[java.lang.NoSuchMethodException] {
      creator.makeJob("com.chollinger.bridgefour.shared.jobs.NotAJob", null)
    }
    assert(
      clue(ex.getMessage).contains(
        "com.chollinger.bridgefour.shared.jobs.NotAJob.<init>(com.chollinger.bridgefour.shared.models.Task$AssignedTaskConfig)"
      )
    )
  }

}
