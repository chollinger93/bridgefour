package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import com.chollinger.bridgefour.kaladin.TestUtils.Http.mockClient
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.shared.models.WorkerStatus
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.dsl.io.*
class HealthMonitorSuite extends CatsEffectSuite {

  test("checkWorkerStatus reports valid status if cluster is up") {
    val client = mockClient()
    for {
      cfg <- Config.load[IO]()
      srv  = HealthMonitorService.make[IO](cfg, client)
      r   <- srv.checkClusterStatus()
      _    = assertEquals(r, Map(0 -> WorkerStatus.Alive))
    } yield ()
  }

  test("checkWorkerStatus reports valid status if cluster is down") {
    val app = HttpRoutes.of[IO] { case GET -> Root => Ok() }.orNotFound // no response
    for {
      cfg <- Config.load[IO]()
      srv  = HealthMonitorService.make[IO](cfg, Client.fromHttpApp(app))
      r   <- srv.checkClusterStatus()
      _    = assertEquals(r, Map(0 -> WorkerStatus.Dead))
    } yield ()
  }

}
