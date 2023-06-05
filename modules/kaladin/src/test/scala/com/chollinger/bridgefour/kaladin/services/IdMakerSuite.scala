package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import munit.CatsEffectSuite

class IdMakerSuite extends CatsEffectSuite {

  test("NaiveUUIDMaker works") {
    val ids = NaiveUUIDMaker.make[IO]()
    for {
      ids <- ids.makeIds(2000)
      _    = assertEquals(ids.distinct.size, ids.size)
    } yield ()
  }

}
