package com.chollinger.bridgefour.shared.extensions

abstract class Consistency        extends scala.annotation.Annotation
case class StaleReads()           extends Consistency
case class EventuallyConsistent() extends Consistency
case class StronglyConsistent()   extends Consistency
