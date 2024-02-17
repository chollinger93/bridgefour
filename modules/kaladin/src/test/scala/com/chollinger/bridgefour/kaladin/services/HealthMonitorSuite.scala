package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import com.chollinger.bridgefour.kaladin.Main.logger
import com.chollinger.bridgefour.kaladin.TestUtils.Http.mockClient
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.shared.exceptions.Exceptions.MisconfiguredClusterException
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Cluster.SlotCountOverview
import com.chollinger.bridgefour.shared.models.ClusterStatus
import com.chollinger.bridgefour.shared.models.IDs.SlotId
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.models.WorkerStatus
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.dsl.io.Ok
import org.http4s.dsl.io.*
class HealthMonitorSuite extends CatsEffectSuite {

  test("checkClusterStatus reports valid status if cluster is up") {
    val client = mockClient()
    for {
      cfg <- Config.load[IO]()
      srv  = HealthMonitorService.make[IO](cfg, client)
      r   <- srv.checkClusterStatus()
      _ = assertEquals(
            r,
            ClusterState(
              status = ClusterStatus.Healthy,
              workers = Map(
                0 -> WorkerState(
                  id = 0,
                  slots = List[SlotState](
                    // from `usedState`
                    SlotState(
                      id = 0,
                      status = ExecutionStatus.NotStarted
                    ),
                    // from `openState`
                    SlotState(
                      id = 1,
                      status = ExecutionStatus.Missing
                    )
                  )
                )
              ),
              slots = SlotCountOverview(open = 1, processing = 0, total = 2)
            )
          )
    } yield ()
  }

  test("checkClusterStatus reports valid status if cluster is down") {
    val app = HttpRoutes.of[IO] { case GET -> Root => Ok() }.orNotFound // no response
    for {
      cfg <- Config.load[IO]()
      srv  = HealthMonitorService.make[IO](cfg, Client.fromHttpApp(app))
      r   <- srv.checkClusterStatus()
      _ = assertEquals(
            r,
            ClusterState(
              status = ClusterStatus.Degraded,
              workers = Map(
                0 -> WorkerState(
                  id = 0,
                  slots = List[SlotState]()
                )
              ),
              slots = SlotCountOverview(open = 0, processing = 0, total = 0)
            )
          )

    } yield ()
  }

  test("checkClusterStatus implodes if workers are misconfigured") {
    val client = mockClient()
    for {
      cfg <- Config.load[IO]()
      w    = cfg.workers.map(w => w.copy(id = 1))
      cfg2 = cfg.copy(workers = w)
      srv  = HealthMonitorService.make[IO](cfg2, client)
      r    = srv.checkClusterStatus()
      _   <- interceptIO[MisconfiguredClusterException](r)
    } yield ()
  }

}
