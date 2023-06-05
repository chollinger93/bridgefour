package com.chollinger.bridgefour.shared.exceptions

object Exceptions {

  class BridgeFourException(msg: String = "")          extends Exception(msg)
  case class InvalidWorkerConfigException(msg: String) extends BridgeFourException(msg)
  case class OrphanTaskException(msg: String) extends BridgeFourException(msg)
  case class NoWorkersAvailableException(msg: String)  extends BridgeFourException(msg)
  case class NoFilesAvailableException(msg: String)    extends BridgeFourException(msg)

}
