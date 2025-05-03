package com.chollinger.bridgefour.shared.jobs

import scala.language.postfixOps

import com.chollinger.bridgefour.shared.models.Job._
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig

// TODO: this is super basic and not very abstracted
trait BridgeFourJob[F[_]] {

  /** Each job must work on exactly one file and write to one directory
    *
    * TODO: this limitations are for simple implementations and somewhat arbitrary
    */

  /** Each job must be able to do computations on the output data */
  def run(): F[BackgroundTaskState]

}
