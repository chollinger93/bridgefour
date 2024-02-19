package com.chollinger.bridgefour.shared.persistence

import cats.Applicative
import cats.effect.Async
import cats.effect.std.MapRef
import cats.implicits.*
import cats.syntax.traverse.toTraverseOps
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError

sealed trait Counter[F[_], K] {

  def inc(key: K): F[Unit]

  def dec(key: K): F[Unit]

  def set(key: K, value: Long): F[Unit]

  def get(key: K): F[Long]

}

object InMemoryCounter {

  private type MR[F[_]] = MapRef[F, Int, Option[Long]]

  private def init[F[_]: Applicative](s: MR[F], size: Int): F[Unit] = for {
    _ <- Range.inclusive(0, size).toList.traverse(i => s(i).set(Some(0L)))
  } yield ()
  def makeF[F[_]: Async: Applicative: ThrowableMonadError](size: Int = 10000) = {
    for {
      storage <- MapRef.ofScalaConcurrentTrieMap[F, Int, Long]
      _       <- init(storage, size)
    } yield new Counter[F, Int]() {
      val err: ThrowableMonadError[F] = implicitly[ThrowableMonadError[F]]

      def inc(key: Int): F[Unit] = storage.updateKeyValueIfSet(key, v => v + 1L)

      def dec(key: Int): F[Unit] = storage.updateKeyValueIfSet(key, v => v - 1L)

      def set(key: Int, value: Long): F[Unit] = storage.updateKeyValueIfSet(key, _ => value)

      def get(key: Int): F[Long] = for {
        r <- storage(key).get
        _ <- err.raiseWhen(r.isEmpty)(new Exception(s"Key $key exceeds counter size $size"))
      } yield r.get
    }
  }

}
