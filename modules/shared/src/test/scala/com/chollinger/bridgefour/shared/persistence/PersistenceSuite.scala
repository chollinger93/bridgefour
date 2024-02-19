package com.chollinger.bridgefour.shared.persistence

import cats.effect.IO
import cats.effect.std.Random
import cats.implicits.*
import com.chollinger.bridgefour.shared.persistence.{Counter, InMemoryCounter}
import munit.CatsEffectSuite

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

class PersistenceSuite extends CatsEffectSuite {

  test("InMemoryPersistence works in a happy path") {
    for {
      p <- InMemoryPersistence.makeF[IO, Int, Int]()
      v <- p.get(0)
      _  = assertEquals(v, None)
      _ <- p.put(0, 0)
      v <- p.get(0)
      _  = assertEquals(v, Some(0))
      _ <- p.put(0, 1)
      v <- p.get(0)
      _  = assertEquals(v, Some(1))
      k <- p.keys()
      _  = assertEquals(k, List(0))
      _ <- p.del(0)
      v <- p.get(0)
      _  = assertEquals(v, None)
    } yield ()
  }

  test("InMemoryPersistence works across threads") {
    Random
      .scalaUtilRandom[IO]
      .map { ev =>
        Range(0, 11).toList.traverse { _ =>
          def countT(ctr: Persistence[IO, Int, Int], i: Int, key: Int): IO[Unit] = for {
            r <- Random[IO](ev).betweenInt(1, 25)
            _ <- IO.sleep(FiniteDuration(r, TimeUnit.MILLISECONDS))
            _ <- ctr.update(0)(key, x => x + 1)
            r <- Random[IO](ev).betweenInt(1, 25)
            _ <- IO.sleep(FiniteDuration(r, TimeUnit.MILLISECONDS))
          } yield ()
          for {
            ctr <- InMemoryPersistence.makeF[IO, Int, Int]()
            f1  <- Range(0, 1000).toList.map(x => countT(ctr, x, 0).start).sequence
            _   <- f1.traverse(_.join)
            r   <- ctr.get(0)
            _    = assertEquals(r, Some(1000))
          } yield ()
        }
      }
      .flatten
  }

}
