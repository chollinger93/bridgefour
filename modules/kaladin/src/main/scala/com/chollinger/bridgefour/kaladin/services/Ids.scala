package com.chollinger.bridgefour.kaladin.services

import cats.Applicative
import cats.effect.Sync
import cats.effect.std.UUIDGen
import cats.implicits.*

trait IdMaker[F[_], A] {

  def makeId(): F[A]

  def makeIds(n: Int): F[List[A]]

}

// WARNING: This is called "Naive" for a reason: It uses the 32bit (collision prone) hash of a UUID
object NaiveUUIDMaker {

  def make[F[_]: Sync: Applicative](): IdMaker[F, Int] = new IdMaker[F, Int]:

    override def makeId(): F[Int] = UUIDGen[F].randomUUID.map(_.hashCode())

    override def makeIds(n: Int): F[List[Int]] = Range.inclusive(0, n).toList.traverse(_ => makeId())

}
