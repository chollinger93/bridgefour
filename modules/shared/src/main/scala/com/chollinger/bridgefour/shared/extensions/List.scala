package com.chollinger.bridgefour.shared.extensions

extension[A] (l: List[A]){

  def takeN(n: Int): (List[A], List[A]) =    (l.take(n), l.takeRight(l.size - n))
}
