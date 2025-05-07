package com.chollinger.bridgefour.shared.jobs

import cats.effect.kernel.Async

import scala.language.postfixOps
import com.chollinger.bridgefour.shared.models.Job.*
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig

// TODO: this is super basic and not very abstracted

/** The base job interface. Since these jobs are summoned, they need a concrete effect type in their implementation
  *
  * @tparam F
  *   Effect, e.g. IO
  */
trait BridgeFourJob[F[_]: Async] {

  /** Each job must work on exactly one file and write to one directory
    *
    * TODO: this limitations are for simple implementations and somewhat arbitrary
    */

  /** Each job must be able to do computations on the output data */
  def run(): F[BackgroundTaskState]

}
