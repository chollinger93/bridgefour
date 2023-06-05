package com.chollinger.bridgefour.shared.persistence

import cats.Applicative
import cats.Monad
import cats.MonadError
import cats.Parallel
import cats.effect.Async
import cats.effect.Ref
import cats.effect.Sync
import cats.effect.kernel.Concurrent
import cats.effect.std.MapRef
import cats.effect.std.Mutex
import cats.implicits.*

import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent
import scala.jdk.CollectionConverters.*
import cats.implicits.toFunctorOps
import cats.syntax.all.toFunctorOps
import cats.syntax.functor.toFunctorOps
import cats.implicits.toFlatMapOps
import cats.syntax.all.toFlatMapOps
import cats.syntax.flatMap.toFlatMapOps

import math.Fractional.Implicits.infixFractionalOps
import math.Integral.Implicits.infixIntegralOps
import math.Numeric.Implicits.infixNumericOps
import cats.implicits.toTraverseOps
import cats.syntax.all.toTraverseOps
import cats.syntax.traverse.toTraverseOps
import com.chollinger.bridgefour.shared.types.Typeclasses.ThrowableMonadError
sealed trait Persistence[F[_], K, V] {

  def put(key: K, value: V): F[Option[V]]

  def get(key: K): F[Option[V]]

  def del(key: K): F[Option[V]]

}

sealed trait Counter[F[_], K] {

  def inc(key: K): F[Unit]

  def dec(key: K): F[Unit]
  
  def set(key: K, value: Long): F[Unit]
  
  def get(key: K): F[Long]
}

object InMemoryPersistence {

  def makeF[F[_]: Sync, K, V](): F[Persistence[F, K, V]] = {
    for {
      storage <- MapRef.ofScalaConcurrentTrieMap[F, K, V]
    } yield new Persistence[F, K, V]() {

      def put(key: K, value: V): F[Option[V]] = storage(key).getAndSet(Some(value))

      def get(key: K): F[Option[V]] = storage(key).get

      def del(key: K): F[Option[V]] = storage(key).getAndSet(None)
    }
  }

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
