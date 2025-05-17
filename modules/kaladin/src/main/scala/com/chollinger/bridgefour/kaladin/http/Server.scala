package com.chollinger.bridgefour.kaladin.http

import cats.Parallel
import cats.data.Kleisli
import cats.effect.std.AtomicCell
import cats.effect.std.Mutex
import cats.effect.Async
import cats.effect.Resource
import cats.effect.kernel.Ref
import com.chollinger.bridgefour.kaladin.models.Config.ServiceConfig
import com.chollinger.bridgefour.kaladin.programs.ClusterControllerImpl
import com.chollinger.bridgefour.kaladin.programs.JobControllerService
import com.chollinger.bridgefour.kaladin.services.*
import com.chollinger.bridgefour.kaladin.state.JobDetailsStateMachine
import com.chollinger.bridgefour.shared.jobs.LeaderCreatorService
import com.chollinger.bridgefour.shared.models.Cluster.ClusterState
import com.chollinger.bridgefour.shared.models.Config.WorkerConfig
import com.chollinger.bridgefour.shared.models.IDs.ClusterId
import com.chollinger.bridgefour.shared.models.IDs.JobId
import com.chollinger.bridgefour.shared.models.IDs.WorkerId
import com.chollinger.bridgefour.shared.models.Job.JobDetails
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.chollinger.bridgefour.shared.persistence.Persistence
import com.comcast.ip4s.*
import fs2.io.net.Network
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger as Http4sLogger
import org.http4s.HttpApp
import org.http4s.Request
import org.http4s.Response
import org.typelevel.log4cats.Logger
import cats.syntax.semigroupk.* // for <+>

object Server {

  def run[F[_]: Async: Parallel: Network: Logger](cfg: ServiceConfig): F[Nothing] = {
    for {
      client      <- EmberClientBuilder.default[F].build
      ids          = NaiveUUIDMaker.make[F]()
      cfgParser    = JobConfigParserService.make[F]()
      splitter     = JobSplitterService.make()
      stateMachine = JobDetailsStateMachine.make(ids, cfgParser, splitter)
      jState      <- Resource.make(InMemoryPersistence.makeF[F, JobId, JobDetails]())(_ => Async[F].unit)
      cState      <- Resource.make(InMemoryPersistence.makeF[F, ClusterId, ClusterState]())(_ => Async[F].unit)
      // TODO: ref
      wState       <- Resource.make(InMemoryPersistence.makeF[F, WorkerId, WorkerConfig]())(_ => Async[F].unit)
      wCache       <- Resource.make(WorkerCache.makeF[F](cfg, wState))(_ => Async[F].unit)
      leader        = LeaderCreatorService.make[F]()
      lock         <- Resource.make(Mutex[F])(_ => Async[F].unit)
      healthMonitor = ClusterOverseer.make[F](wCache, client)
      jobController = JobControllerService(client, jState, stateMachine, leader, cfg)
      clusterController =
        ClusterControllerImpl(client, healthMonitor, splitter, jState, cState, wCache, ids, stateMachine, lock, cfg)
      // Kaladin's main threads
      _ <- Resource.make(clusterController.startFibers())(_ => Async[F].unit).start
      // Raft
      raftLock  <- Resource.make(Mutex[F])(_ => Async[F].unit)
      raftState <- Resource.make(AtomicCell[F].of(RaftElectionState(cfg.self.id, cfg.leaders)))(_ => Async[F].unit)
      raftSvc    = RaftService.make[F](client, raftLock, raftState)
      _         <- Resource.make(raftSvc.runFibers())(_ => Async[F].unit).start
      // External interface
      httpApp: Kleisli[F, Request[F], Response[F]] =
        (LeaderRoutes[F](jobController, healthMonitor).routes <+> RaftRoutes[F](raftSvc).routes).orNotFound

      // With Middlewares in place
      finalHttpApp: HttpApp[F] = Http4sLogger.httpApp(true, true)(httpApp)

      _ <- EmberServerBuilder
             .default[F]
             // TODO: scala3 + pureconfig = no ip4s types in config?
             .withHost(Host.fromString(cfg.self.host).get)
             .withPort(Port.fromInt(cfg.self.port).get)
             .withHttpApp(finalHttpApp)
             .build
    } yield ()
  }.useForever

}
