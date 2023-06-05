package com.chollinger.bridgefour.kaladin.services

import java.io.File

import cats.effect.Sync
import com.chollinger.bridgefour.shared.models.Job.JobConfig

trait JobConfigParser[F[_]] {

  def splitJobIntoFiles(config: JobConfig): F[List[File]]

}

object JobConfigParserService {

  def make[F[_]: Sync](): JobConfigParser[F] = new JobConfigParser[F] {

    val sF: Sync[F] = implicitly[Sync[F]]

    override def splitJobIntoFiles(cfg: JobConfig): F[List[File]] = sF.blocking {
      val dir = new File(cfg.input)
      if (dir.exists && dir.isDirectory) {
        dir.listFiles.toList
      } else {
        List.empty
      }
    }

  }

}
