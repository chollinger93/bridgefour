package com.chollinger.bridgefour.shared.persistence

import cats.effect.IO
import cats.effect.std.Random
import cats.implicits.*
import com.chollinger.bridgefour.shared.persistence.{Counter, InMemoryCounter}
import munit.CatsEffectSuite

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class PersistenceSuite extends CatsEffectSuite {

  test("InMemoryCounter counts up, down, handles misses") {
    for {
      ctr <- InMemoryCounter.makeF[IO]()
      _   <- ctr.inc(0)
      v   <- ctr.get(0)
      _    = assertEquals(v, 1L)
      _   <- ctr.dec(0)
      v   <- ctr.get(0)
      _    = assertEquals(v, 0L)
      // A miss here is valid, since the default size is 10k
      v <- ctr.get(1)
      _  = assertEquals(v, 0L)
    } yield ()
  }

  test("Set works") {
    val id = 0
    for {
      ctr <- InMemoryCounter.makeF[IO]()
      _   <- ctr.inc(id)
      v   <- ctr.get(id)
      _    = assertEquals(v, 1L)
      _   <- ctr.set(id, 50)
      v   <- ctr.get(id)
      _    = assertEquals(v, 50L)
      _   <- ctr.inc(id)
      v   <- ctr.get(id)
      _    = assertEquals(v, 51L)
    } yield ()
  }

  test("InMemoryCounter works across threads") {
    Random
      .scalaUtilRandom[IO]
      .map { ev =>
        Range(0, 11).toList.traverse { _ =>
          def countT(ctr: Counter[IO, Int], i: Int, key: Int = 0): IO[Unit] = for {
            r <- Random[IO](ev).betweenInt(1, 25)
            _ <- IO.sleep(FiniteDuration(r, TimeUnit.MILLISECONDS))
            _ <- if (i == 1) ctr.inc(key) else ctr.dec(key)
            r <- Random[IO](ev).betweenInt(1, 25)
            _ <- IO.sleep(FiniteDuration(r, TimeUnit.MILLISECONDS))
          } yield ()
          for {
            ctr <- InMemoryCounter.makeF[IO]()
            f1  <- Range(0, 1001).toList.map(_ => countT(ctr, 1, 0).start).sequence
            f2  <- countT(ctr, -1).start
            _   <- f1.traverse(_.join)
            _   <- f2.join
            r   <- ctr.get(0)
            _    = assertEquals(r, 1000L)
          } yield ()
        }
      }
      .flatten
  }

  test("InMemoryCounter works with size <= N") {
    for {
      ctr <- InMemoryCounter.makeF[IO](size = 1)
      _   <- ctr.inc(0)
      v   <- ctr.get(0)
      _    = assertEquals(v, 1L)
      _   <- ctr.inc(10)
      _    = assertIO(ctr.get(10).handleError(_ => Integer.MAX_VALUE.toLong), Integer.MAX_VALUE.toLong)
    } yield ()
  }

}
