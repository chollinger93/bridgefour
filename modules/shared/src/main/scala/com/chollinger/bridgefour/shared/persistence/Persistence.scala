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

  def put(key: K, value: V): F[Unit]

  def update(identity: V)(key: K, v: V => V): F[Unit]

  def get(key: K): F[Option[V]]

  def del(key: K): F[Option[V]]

  def keys(): F[List[K]]

  def values(): F[List[V]]

  def list(): F[Map[K, V]]

}

object InMemoryPersistence {

  def makeF[F[_]: Sync, K, V](): F[Persistence[F, K, V]] = {
    val data = new ConcurrentHashMap[K, V]()
    for {
      storage <- Ref.of[F, Map[K, V]](data.asScala.toMap)
    } yield {
      new Persistence[F, K, V]() {

        def put(key: K, value: V): F[Unit] = storage.getAndUpdate(_.updated(key, value)).void

        def update(identity: V)(key: K, v: V => V): F[Unit] = storage
          .getAndUpdate(m => m.updated(key, v(m.getOrElse(key, identity))))
          .void

        def get(key: K): F[Option[V]] = storage.get.map(_.get(key))

        def del(key: K): F[Option[V]] = for {
          old <- storage.get.map(_.get(key))
          _   <- storage.update(_.removed(key))
        } yield old

        def keys(): F[List[K]] = storage.get.map(_.keys.toList)

        def values(): F[List[V]] = storage.get.map(_.values.toList)

        def list(): F[Map[K, V]] = storage.get
      }
    }
  }

}
