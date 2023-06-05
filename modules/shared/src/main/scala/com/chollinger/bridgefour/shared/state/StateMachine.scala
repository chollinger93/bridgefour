package com.chollinger.bridgefour.shared.state

trait StateMachine[S, E] {

  def transition(initState: S, event: E): S

}

trait StateMachineWithAction[S, E, A] {

  def transition(initState: S, event: E, action: (S, A) => A): A

}
