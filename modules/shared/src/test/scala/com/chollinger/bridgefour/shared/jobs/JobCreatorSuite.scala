package com.chollinger.bridgefour.shared.jobs

import cats.effect.IO
import cats.effect.Sync
import com.chollinger.bridgefour.shared.TestJobs
import com.chollinger.bridgefour.shared.models.Job
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class NotAJob[F[_]]() {}

class JobCreatorSuite extends CatsEffectSuite {

  given unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  private lazy val creator = BridgeFourJobCreatorService.make[IO]()

  test("BridgeFourJobCreator creates a valid job") {
    // If it doesn't panic, it's fine
    val job = creator.makeJob(TestJobs.sampleJobClass)
    assert(job != null)
  }

  test("BridgeFourJobCreator doesn't create an missing job") {
    val ex = intercept[java.lang.ClassNotFoundException] {
      creator.makeJob("fake.class")
    }
    assert(clue(ex.getMessage).contains("fake.class"))
  }

  test("BridgeFourJobCreator doesn't create an invalid job") {
    val ex = intercept[java.lang.NoSuchMethodException] {
      creator.makeJob("com.chollinger.bridgefour.shared.jobs.NotAJob")
    }
    assert(
      clue(ex.getMessage).contains(
        "com.chollinger.bridgefour.shared.jobs.NotAJob.<init>(scala.collection.immutable.Map)"
      )
    )
  }

}
