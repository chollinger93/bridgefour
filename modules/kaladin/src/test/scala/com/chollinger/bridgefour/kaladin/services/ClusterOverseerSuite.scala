package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import com.chollinger.bridgefour.kaladin.Main.logger
import com.chollinger.bridgefour.kaladin.TestUtils.Http.{halfUsedWorkerState, mockClient}
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.shared.exceptions.Exceptions.MisconfiguredClusterException
import com.chollinger.bridgefour.shared.models.Cluster.{ClusterState, SlotCountOverview}
import com.chollinger.bridgefour.shared.models.ClusterStatus
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.dsl.io._
class ClusterOverseerSuite extends CatsEffectSuite {

  test("checkClusterStatus reports valid status if cluster is up") {
    val client = mockClient()
    for {
      cfg <- Config.load[IO]()
      srv  = ClusterOverseer.make[IO](cfg, client)
      r   <- srv.getClusterState()
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
              slots = SlotCountOverview(open = 1, total = 2)
            )
          )
    } yield ()
  }

  test("checkClusterStatus reports valid status if cluster is down") {
    val app = HttpRoutes.of[IO] { case GET -> Root => Ok() }.orNotFound // no response
    for {
      cfg <- Config.load[IO]()
      srv  = ClusterOverseer.make[IO](cfg, Client.fromHttpApp(app))
      r   <- srv.getClusterState()
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
              slots = SlotCountOverview(open = 0, total = 0)
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
      srv  = ClusterOverseer.make[IO](cfg2, client)
      r    = srv.getClusterState()
      _   <- interceptIO[MisconfiguredClusterException](r)
    } yield ()
  }

  test("WorkerOverseerService.getWorkerState reports valid state") {
    for {
      cfg <- Config.load[IO]()
      srv  = ClusterOverseer.make[IO](cfg, mockClient(halfUsedWorkerState))
      _   <- assertIO(srv.getWorkerState(cfg.workers.head), halfUsedWorkerState)
    } yield ()
  }

}
