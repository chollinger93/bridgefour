package com.chollinger.bridgefour.shared.persistence

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._

import cats.effect.Ref
import cats.effect.Sync
import cats.implicits.toFlatMapOps
import cats.implicits.toFunctorOps

sealed trait ReadOnlyPersistence[F[_], K, V] {

  def get(key: K): F[Option[V]]

  def keys(): F[List[K]]

  def values(): F[List[V]]

  def list(): F[Map[K, V]]

}
sealed trait Persistence[F[_], K, V] extends ReadOnlyPersistence[F, K, V] {

  def put(key: K, value: V): F[Unit]

  def update(identity: V)(key: K, v: V => V): F[Unit]

  def del(key: K): F[Option[V]]

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
