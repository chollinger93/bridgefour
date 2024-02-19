package com.chollinger.bridgefour.shared.background

import cats.effect.{IO, Sync, SyncIO}
import cats.effect.kernel.Fiber
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.background.BackgroundWorker.FiberContainer
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import munit.CatsEffectSuite
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

// TODO: consider a Supervisor[F]
class BackgroundWorkerSuite extends CatsEffectSuite {

  implicit def unsafeLogger[F[_]: Sync]: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
  private val stateF                                                  = InMemoryPersistence.makeF[IO, Long, FiberContainer[IO, Int, String]]()

  test("Background worker gets result of a task") {
    def test(): IO[Int] = IO.println("Starting") >> IO.sleep(50 millisecond) >> IO.pure(1) <* IO.println("Done")
    for {
      state <- stateF
      worker = BackgroundWorkerService.make[IO, Int, String](state)
      _     <- worker.start(0, test())
      r     <- worker.get(0)
      _      = assertEquals(r.isDefined, true)
      res   <- worker.getResult(0)
      _      = assertEquals(res.res.toOption.isDefined, true)
      _      = assertEquals(res.res.toOption.get, 1)
      // No metadata
      _ = assertEquals(res.meta, None)
    } yield ()
  }

  test("BackgroundWorkerService.probeResult gets metadata, even if the task won't finish") {
    def neverFinish(): IO[Int] = IO.println("Starting") >> IO.sleep(50 hour) >> IO.pure(1) <* IO.println("Done")

    for {
      state <- stateF
      worker = BackgroundWorkerService.make[IO, Int, String](state)
      _     <- worker.start(0, neverFinish(), Some("metadata"))
      r     <- worker.get(0)
      _      = assertEquals(r.isDefined, true)
      res   <- worker.probeResult(0, 1 millisecond)
      // No result yet
      _ = assertEquals(res.res.left.toOption.get, ExecutionStatus.InProgress)
      _ = assertEquals(res.res.toOption.isDefined, false)
      _ = assertEquals(res.meta.get, "metadata")
    } yield ()
  }

  test("Background worker takes multiple tasks") {
    def test(id: Int): IO[Int] =
      IO.println(s"Starting $id") >> IO.sleep(50 millisecond) >> IO.pure(id) <* IO.println(s"Done $id")

    for {
      state <- stateF
      worker = BackgroundWorkerService.make[IO, Int, String](state)
      _     <- worker.start(0, test(0))
      _     <- worker.start(1, test(1))
      r     <- worker.get(0)
      _      = assertEquals(r.isDefined, true)
      r     <- worker.get(1)
      _      = assertEquals(r.isDefined, true)
      res   <- worker.getResult(0)
      _      = assertEquals(res.res.toOption.get, 0)
      res   <- worker.getResult(1)
      _      = assertEquals(res.res.toOption.get, 1)
    } yield ()
  }

  test("Background worker reports cache misses") {
    for {
      state <- stateF
      worker = BackgroundWorkerService.make[IO, Int, String](state)
      r     <- worker.get(1)
      _      = assertEquals(r.isDefined, false)
      res   <- worker.getResult(1)
      _      = assertEquals(res.res.toOption.isDefined, false)
      _      = assertEquals(res.res.left.toOption.get, ExecutionStatus.Missing)
      _      = assertEquals(res.meta, None)
    } yield ()
  }

  test("BackgroundWorkerService.get gets correct results if the task fails") {
    def fails(): IO[Int] = IO.raiseError(new Exception("Boom"))

    for {
      state <- stateF
      worker = BackgroundWorkerService.make[IO, Int, String](state)
      _     <- worker.start(0, fails(), Some("metadata"))
      r     <- worker.get(0)
      _      = assertEquals(r.isDefined, true)
      res   <- worker.getResult(0)
      _      = assertEquals(res.res.toOption.isDefined, false)
      _      = assertEquals(res.res.left.toOption.get, ExecutionStatus.Error)
      _      = assertEquals(res.meta.get, "metadata")
    } yield ()
  }

}
