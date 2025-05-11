package com.chollinger.bridgefour.kaladin.services

import cats.effect.IO
import com.chollinger.bridgefour.kaladin.Main.logger
import com.chollinger.bridgefour.kaladin.TestUtils.Http.halfUsedWorkerState
import com.chollinger.bridgefour.kaladin.TestUtils.Http.mockClient
import com.chollinger.bridgefour.kaladin.models.Config
import com.chollinger.bridgefour.shared.exceptions.Exceptions.MisconfiguredClusterException
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Cluster.SlotCountOverview
import com.chollinger.bridgefour.shared.models.ClusterStatus
import com.chollinger.bridgefour.shared.models.WorkerStatus
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.States.SlotState
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Worker.WorkerState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.dsl.io.*
class ClusterOverseerSuite extends CatsEffectSuite {

  test("checkClusterStatus reports valid status if cluster is up") {
    val client = mockClient()
    for {
      cfg    <- Config.load[IO]()
      wState <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache <- WorkerCache.makeF[IO](cfg, wState)
      srv     = ClusterOverseer.make[IO](wCache, client)
      r      <- srv.getClusterState()
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
      cfg    <- Config.load[IO]()
      wState <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache <- WorkerCache.makeF[IO](cfg, wState)
      srv     = ClusterOverseer.make[IO](wCache, Client.fromHttpApp(app))
      r      <- srv.getClusterState()
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
      cfg    <- Config.load[IO]()
      wState <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      w       = cfg.workers.map(w => w.copy(id = 1))
      cfg2    = cfg.copy(workers = w)
      wCache <- WorkerCache.makeF[IO](cfg2, wState)
      srv     = ClusterOverseer.make[IO](wCache, client)
      r       = srv.getClusterState()
      _      <- interceptIO[MisconfiguredClusterException](r)
    } yield ()
  }

  test("WorkerOverseerService.getWorkerState reports valid state") {
    for {
      cfg    <- Config.load[IO]()
      wState <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache <- WorkerCache.makeF[IO](cfg, wState)
      srv     = ClusterOverseer.make[IO](wCache, mockClient(halfUsedWorkerState))
      _      <- assertIO(srv.getWorkerState(cfg.workers.head), halfUsedWorkerState)
    } yield ()
  }

  test("WorkerOverseerService.addWorker adds valid workers") {
    val client = mockClient()
    for {
      cfg    <- Config.load[IO]()
      wState <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache <- WorkerCache.makeF[IO](cfg, wState)
      srv     = ClusterOverseer.make[IO](wCache, client)
      state <- srv.addWorker(
                 WorkerConfig(
                   id = 0,
                   schema = "http",
                   host = "horst",
                   port = 1111
                 )
               )
      // This depends on the mock client
      _ = assertEquals(
            state,
            WorkerState(
              id = 0,
              slots = List(
                SlotState(
                  id = 0,
                  status = ExecutionStatus.NotStarted
                ),
                SlotState(
                  id = 1,
                  status = ExecutionStatus.Missing
                )
              )
            )
          )
    } yield ()
  }

  test("WorkerOverseerService.addWorker doesn't add invalid workers") {
    val client = mockClient()
    for {
      cfg    <- Config.load[IO]()
      wState <- InMemoryPersistence.makeF[IO, WorkerId, WorkerConfig]()
      wCache <- WorkerCache.makeF[IO](cfg, wState)
      srv     = ClusterOverseer.make[IO](wCache, client)
      // Expected ID 10 from http://horst:7777 but got 0
      _ <- interceptIO[MisconfiguredClusterException](
             srv.addWorker(
               WorkerConfig(
                 id = 10,
                 schema = "http",
                 host = "horst",
                 port = 7777
               )
             )
           )
    } yield ()
  }

}
